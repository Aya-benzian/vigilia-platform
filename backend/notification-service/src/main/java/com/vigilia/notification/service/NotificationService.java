package com.vigilia.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilia.notification.dto.*;
import com.vigilia.notification.entity.NotificationEntity;
import com.vigilia.notification.entity.NotificationPreferenceEntity;
import com.vigilia.notification.entity.ProcessedEventEntity;
import com.vigilia.notification.exception.ResourceNotFoundException;
import com.vigilia.notification.repository.NotificationPreferenceRepository;
import com.vigilia.notification.repository.NotificationRepository;
import com.vigilia.notification.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final ObjectMapper objectMapper;
    private final BrevoEmailService brevoEmailService;
    private final ProcessedEventRepository processedEventRepository;

    public NotificationService(
            NotificationRepository notificationRepository,
            NotificationPreferenceRepository preferenceRepository,
            ObjectMapper objectMapper,
            BrevoEmailService brevoEmailService) {
        this(notificationRepository, preferenceRepository, objectMapper, brevoEmailService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public NotificationService(
            NotificationRepository notificationRepository,
            NotificationPreferenceRepository preferenceRepository,
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Autowired(required = false) BrevoEmailService brevoEmailService,
            @org.springframework.beans.factory.annotation.Autowired(required = false) ProcessedEventRepository processedEventRepository) {
        this.notificationRepository = notificationRepository;
        this.preferenceRepository = preferenceRepository;
        this.objectMapper = objectMapper;
        this.brevoEmailService = brevoEmailService;
        this.processedEventRepository = processedEventRepository;
    }

    @Transactional(readOnly = true)
    public NotificationListResponse listNotifications(
            UUID userId,
            String category,
            String status,
            String search,
            int page,
            int size) {

        List<NotificationEntity> all = notificationRepository.findByUserIdOrderByCreatedAtDesc(userId);

        long unreadCount = 0;
        Map<String, Long> categoryCounts = new LinkedHashMap<>();
        categoryCounts.put("ALL", (long) all.size());
        categoryCounts.put("UNREAD", 0L);
        categoryCounts.put("TRANSACTION", 0L);
        categoryCounts.put("REVIEW", 0L);
        categoryCounts.put("SECURITY", 0L);
        categoryCounts.put("SYSTEM", 0L);

        for (NotificationEntity n : all) {
            if (!n.isRead()) {
                unreadCount++;
            }
            String cat = normalizeCategory(n.getCategory());
            categoryCounts.put(cat, categoryCounts.getOrDefault(cat, 0L) + 1L);
        }
        categoryCounts.put("UNREAD", unreadCount);

        String catFilter = (category != null && !category.isBlank() && !"ALL".equalsIgnoreCase(category))
                ? category.trim().toUpperCase()
                : null;
        String statusFilter = (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status))
                ? status.trim().toUpperCase()
                : null;
        String q = (search != null && !search.isBlank()) ? search.trim().toLowerCase() : null;

        List<NotificationEntity> filtered = all.stream()
                .filter(n -> catFilter == null || catFilter.equalsIgnoreCase(n.getCategory()))
                .filter(n -> {
                    if ("UNREAD".equals(statusFilter)) return !n.isRead();
                    if ("READ".equals(statusFilter)) return n.isRead();
                    return true;
                })
                .filter(n -> {
                    if (q == null) return true;
                    return (n.getTitle() != null && n.getTitle().toLowerCase().contains(q))
                            || (n.getMessage() != null && n.getMessage().toLowerCase().contains(q))
                            || (n.getEventType() != null && n.getEventType().toLowerCase().contains(q));
                })
                .toList();

        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(0, page);
        int totalFiltered = filtered.size();
        int totalPages = totalFiltered == 0 ? 1 : (int) Math.ceil((double) totalFiltered / safeSize);

        int fromIdx = Math.min(safePage * safeSize, totalFiltered);
        int toIdx = Math.min(fromIdx + safeSize, totalFiltered);

        List<NotificationResponse> items = filtered.subList(fromIdx, toIdx).stream()
                .map(this::toResponse)
                .toList();

        return new NotificationListResponse(
                items,
                unreadCount,
                all.size(),
                categoryCounts,
                safePage,
                safeSize,
                totalPages
        );
    }

    @Transactional(readOnly = true)
    public UnreadCountResponse getUnreadCount(UUID userId) {
        long unread = notificationRepository.countByUserIdAndReadFalse(userId);
        long critical = notificationRepository.countByUserIdAndReadFalseAndSeverityIn(
                userId, List.of("CRITICAL", "WARNING"));
        return new UnreadCountResponse(unread, critical);
    }

    @Transactional
    public NotificationResponse createFromEvent(NotificationCreateRequest request) {
        if (request.eventId() != null) {
            if (processedEventRepository != null && processedEventRepository.existsById(request.eventId())) {
                return notificationRepository.findByEventId(request.eventId())
                        .map(this::toResponse)
                        .orElse(null);
            }
            Optional<NotificationEntity> existing = notificationRepository.findByEventId(request.eventId());
            if (existing.isPresent()) {
                return toResponse(existing.get());
            }
        }

        String eventType = request.eventType().trim().toUpperCase();
        String category = resolveCategory(eventType);
        String severity = resolveSeverity(eventType, request.metadata());

        NotificationPreferenceEntity prefs = getOrCreatePreferences(request.customerId());
        if (!shouldDeliverInApp(category, severity, prefs)) {
            log.info("Notification skipped due to user preferences [userId={}, category={}]",
                    request.customerId(), category);
            if (processedEventRepository != null && request.eventId() != null) {
                processedEventRepository.save(new ProcessedEventEntity(request.eventId(), eventType));
            }
            return null;
        }

        NotificationEntity entity = new NotificationEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(request.customerId());
        entity.setEventId(request.eventId() != null ? request.eventId() : UUID.randomUUID());
        entity.setEventType(eventType);
        entity.setCategory(category);
        entity.setSeverity(severity);
        entity.setChannel(request.channel() != null && !request.channel().isBlank()
                ? request.channel().trim().toUpperCase()
                : "IN_APP");
        entity.setTitle(request.title().trim());
        entity.setMessage(request.message().trim());
        entity.setTransactionId(request.transactionId());
        entity.setMetadata(serializeMetadata(request.metadata()));
        entity.setRead(false);
        entity.setCreatedAt(request.timestamp() != null ? request.timestamp() : Instant.now());

        NotificationEntity saved = notificationRepository.save(entity);
        if (processedEventRepository != null && request.eventId() != null) {
            processedEventRepository.save(new ProcessedEventEntity(entity.getEventId(), eventType));
        }
        log.info("Notification persisted [id={}, userId={}, eventType={}, category={}]",
                saved.getId(), saved.getUserId(), saved.getEventType(), saved.getCategory());

        if (brevoEmailService != null && shouldDeliverEmail(category, severity, prefs)) {
            brevoEmailService.sendNotificationEmailAsync(saved, request.metadata());
        }
        return toResponse(saved);
    }

    private boolean shouldDeliverEmail(String category, String severity, NotificationPreferenceEntity prefs) {
        if (!prefs.isEmailNotifications()) {
            return false;
        }
        if ("CRITICAL".equals(severity)) {
            return true;
        }
        return switch (category) {
            case "TRANSACTION" -> prefs.isTransactionUpdates();
            case "REVIEW" -> prefs.isTransferReviews();
            case "SECURITY" -> prefs.isSecurityAlerts();
            default -> true;
        };
    }

    @Transactional
    public NotificationResponse createFromOutboxEvent(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("Outbox payload must be a JSON object");
        }
        String eventType = payload.path("eventType").asText("TRANSACTION_COMPLETED");
        String customerIdStr = payload.path("customerId").asText(null);
        if (customerIdStr == null || customerIdStr.isBlank()) {
            throw new IllegalArgumentException("customerId is required in outbox payload");
        }
        UUID customerId = UUID.fromString(customerIdStr);
        UUID eventId = payload.hasNonNull("eventId")
                ? UUID.fromString(payload.get("eventId").asText())
                : UUID.randomUUID();
        UUID transactionId = payload.hasNonNull("transactionId")
                ? UUID.fromString(payload.get("transactionId").asText())
                : null;

        String amount = payload.hasNonNull("amount") ? payload.get("amount").asText() : null;
        String currency = payload.path("currency").asText("EUR");
        String reason = payload.path("reason").asText("");
        String txType = payload.path("transactionType").asText("TRANSACTION");

        String title;
        String message;
        String formattedAmount = amount != null ? amount + " " + currency : "";

        switch (eventType.toUpperCase()) {
            case "TRANSFER_REVIEW_REQUIRED" -> {
                title = "Transfer Under Review";
                message = "Your transfer" + (formattedAmount.isEmpty() ? "" : " of " + formattedAmount)
                        + " is under review by our security team."
                        + (reason.isBlank() ? "" : " Reason: " + reason);
            }
            case "PAYMENT_REVIEW_REQUIRED" -> {
                title = "Payment Under Review";
                message = "Your payment" + (formattedAmount.isEmpty() ? "" : " of " + formattedAmount)
                        + " is under review by our security team."
                        + (reason.isBlank() ? "" : " Reason: " + reason);
            }
            case "TRANSACTION_PENDING_REVIEW", "TRANSACTION_SUSPENDED_REVIEW" -> {
                title = "Transfer Under Review";
                message = "Your " + txType.toLowerCase() + (formattedAmount.isEmpty() ? "" : " of " + formattedAmount)
                        + " is under review by our security team."
                        + (reason.isBlank() ? "" : " Reason: " + reason);
            }
            case "TRANSACTION_REJECTED" -> {
                title = "Transaction Declined";
                message = "Your " + txType.toLowerCase() + (formattedAmount.isEmpty() ? "" : " of " + formattedAmount)
                        + " was declined."
                        + (reason.isBlank() ? "" : " " + reason);
            }
            case "TRANSACTION_RESOLVED" -> {
                title = "Review Completed";
                message = "The security review on your " + txType.toLowerCase()
                        + (formattedAmount.isEmpty() ? "" : " of " + formattedAmount) + " has been resolved.";
            }
            default -> {
                title = "Transaction Approved";
                message = "Your " + txType.toLowerCase() + (formattedAmount.isEmpty() ? "" : " of " + formattedAmount)
                        + " was approved and processed successfully.";
            }
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        if (amount != null) meta.put("amount", amount);
        meta.put("currency", currency);
        meta.put("transactionType", txType);
        if (!reason.isBlank()) meta.put("reason", reason);

        return createFromEvent(new NotificationCreateRequest(
                eventId,
                eventType,
                Instant.now(),
                customerId,
                transactionId,
                "IN_APP",
                title,
                message,
                meta
        ));
    }

    @Transactional
    public NotificationResponse markAsRead(UUID userId, UUID notificationId) {
        NotificationEntity entity = notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        if (!entity.isRead()) {
            entity.setRead(true);
            entity.setReadAt(Instant.now());
            entity = notificationRepository.save(entity);
        }
        return toResponse(entity);
    }

    @Transactional
    public NotificationResponse markAsUnread(UUID userId, UUID notificationId) {
        NotificationEntity entity = notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        if (entity.isRead()) {
            entity.setRead(false);
            entity.setReadAt(null);
            entity = notificationRepository.save(entity);
        }
        return toResponse(entity);
    }

    @Transactional
    public int markAllAsRead(UUID userId) {
        return notificationRepository.markAllAsReadByUserId(userId, Instant.now());
    }

    @Transactional
    public void dismissNotification(UUID userId, UUID notificationId) {
        NotificationEntity entity = notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        notificationRepository.delete(entity);
    }

    @Transactional
    public int clearReadNotifications(UUID userId) {
        return notificationRepository.deleteAllReadByUserId(userId);
    }

    @Transactional
    public NotificationPreferenceResponse getPreferences(UUID userId) {
        return toPreferenceResponse(getOrCreatePreferences(userId));
    }

    @Transactional
    public NotificationPreferenceResponse updatePreferences(UUID userId, NotificationPreferenceUpdateRequest req) {
        NotificationPreferenceEntity prefs = getOrCreatePreferences(userId);
        if (req.transactionUpdates() != null) prefs.setTransactionUpdates(req.transactionUpdates());
        if (req.securityAlerts() != null) prefs.setSecurityAlerts(req.securityAlerts());
        if (req.transferReviews() != null) prefs.setTransferReviews(req.transferReviews());
        if (req.promotionalUpdates() != null) prefs.setPromotionalUpdates(req.promotionalUpdates());
        if (req.emailNotifications() != null) prefs.setEmailNotifications(req.emailNotifications());
        if (req.inAppNotifications() != null) prefs.setInAppNotifications(req.inAppNotifications());
        return toPreferenceResponse(preferenceRepository.save(prefs));
    }

    private NotificationPreferenceEntity getOrCreatePreferences(UUID userId) {
        return preferenceRepository.findById(userId).orElseGet(() -> {
            NotificationPreferenceEntity def = new NotificationPreferenceEntity();
            def.setUserId(userId);
            def.setTransactionUpdates(true);
            def.setSecurityAlerts(true);
            def.setTransferReviews(true);
            def.setPromotionalUpdates(false);
            def.setEmailNotifications(true);
            def.setInAppNotifications(true);
            return preferenceRepository.save(def);
        });
    }

    private boolean shouldDeliverInApp(String category, String severity, NotificationPreferenceEntity prefs) {
        if ("CRITICAL".equals(severity) || "SECURITY".equals(category)) {
            return prefs.isSecurityAlerts() || "CRITICAL".equals(severity);
        }
        if (!prefs.isInAppNotifications()) {
            return false;
        }
        return switch (category) {
            case "TRANSACTION" -> prefs.isTransactionUpdates();
            case "REVIEW" -> prefs.isTransferReviews();
            case "SYSTEM" -> prefs.isPromotionalUpdates();
            default -> true;
        };
    }

    private String resolveCategory(String eventType) {
        if (eventType.contains("SECURITY") || eventType.contains("LOGIN")
                || eventType.contains("PASSWORD") || eventType.contains("LOCKED")) {
            return "SECURITY";
        }
        if (eventType.contains("REVIEW") || eventType.contains("SUSPENDED")) {
            return "REVIEW";
        }
        if (eventType.contains("PROMO") || eventType.contains("SYSTEM")) {
            return "SYSTEM";
        }
        return "TRANSACTION";
    }

    private String resolveSeverity(String eventType, Map<String, Object> metadata) {
        if (metadata != null && metadata.get("severity") instanceof String s && !s.isBlank()) {
            return s.trim().toUpperCase();
        }
        if (eventType.contains("REJECTED") || eventType.contains("DECLINED")
                || eventType.contains("BLOCKED") || eventType.contains("LOCKED")) {
            return "CRITICAL";
        }
        if (eventType.contains("REVIEW") || eventType.contains("SUSPENDED")
                || eventType.contains("WARN") || eventType.contains("FAILED")) {
            return "WARNING";
        }
        if (eventType.contains("SECURITY") || eventType.contains("LOGIN")) {
            return "INFO";
        }
        if (eventType.contains("APPROVED") || eventType.contains("COMPLETED") || eventType.contains("RESOLVED")) {
            return "SUCCESS";
        }
        return "INFO";
    }

    private String normalizeCategory(String category) {
        if (category == null) return "SYSTEM";
        String upper = category.toUpperCase();
        return switch (upper) {
            case "TRANSACTION", "REVIEW", "SECURITY", "SYSTEM" -> upper;
            default -> "SYSTEM";
        };
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private Map<String, Object> deserializeMetadata(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(raw, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private NotificationResponse toResponse(NotificationEntity e) {
        return new NotificationResponse(
                e.getId(),
                e.getUserId(),
                e.getEventId(),
                e.getEventType(),
                e.getCategory(),
                e.getSeverity(),
                e.getChannel(),
                e.getTitle(),
                e.getMessage(),
                e.getTransactionId(),
                deserializeMetadata(e.getMetadata()),
                e.isRead(),
                e.getReadAt(),
                e.getCreatedAt()
        );
    }

    private NotificationPreferenceResponse toPreferenceResponse(NotificationPreferenceEntity p) {
        return new NotificationPreferenceResponse(
                p.getUserId(),
                p.isTransactionUpdates(),
                p.isSecurityAlerts(),
                p.isTransferReviews(),
                p.isPromotionalUpdates(),
                p.isEmailNotifications(),
                p.isInAppNotifications(),
                p.getUpdatedAt()
        );
    }
}

