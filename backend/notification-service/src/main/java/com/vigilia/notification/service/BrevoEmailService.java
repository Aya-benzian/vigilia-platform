package com.vigilia.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.vigilia.notification.entity.NotificationEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Dispatches transactional and security alert emails via Brevo HTTPS REST API (port 443),
 * which is GKE-compatible without requiring outbound SMTP ports.
 */
@Service
public class BrevoEmailService {

    private static final Logger log = LoggerFactory.getLogger(BrevoEmailService.class);

    private final boolean enabled;
    private final String apiUrl;
    private final String apiKey;
    private final String senderEmail;
    private final String senderName;
    private final RestClient brevoRestClient;
    private final RestClient customerRestClient;

    @Autowired
    public BrevoEmailService(
            @Autowired(required = false) RestClient.Builder restClientBuilder,
            @Value("${brevo.enabled:false}") boolean enabled,
            @Value("${brevo.api.url:https://api.brevo.com/v3/smtp/email}") String apiUrl,
            @Value("${brevo.api.key:}") String apiKey,
            @Value("${brevo.sender.email:}") String senderEmail,
            @Value("${brevo.sender.name:Vigilia Security}") String senderName,
            @Value("${customer.service.url:http://localhost:8082}") String customerServiceUrl) {
        this.enabled = enabled;
        this.apiUrl = apiUrl;
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.senderEmail = senderEmail != null ? senderEmail.trim() : "";
        this.senderName = senderName != null ? senderName.trim() : "Vigilia Security";

        RestClient.Builder builder = restClientBuilder != null ? restClientBuilder : RestClient.builder();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(4));
        factory.setReadTimeout(Duration.ofSeconds(8));

        this.brevoRestClient = builder.clone().requestFactory(factory).build();
        this.customerRestClient = (!customerServiceUrl.isBlank())
                ? builder.clone().baseUrl(customerServiceUrl.trim()).requestFactory(factory).build()
                : null;
    }

    public void sendNotificationEmailAsync(NotificationEntity notification, Map<String, Object> metadata) {
        if (!enabled || apiKey.isBlank() || senderEmail.isBlank()) {
            return;
        }
        CompletableFuture.runAsync(() -> sendInternal(notification, metadata));
    }

    private void sendInternal(NotificationEntity notification, Map<String, Object> metadata) {
        try {
            String recipientEmail = resolveRecipientEmail(notification.getUserId(), metadata);
            if (recipientEmail == null || recipientEmail.isBlank()) {
                log.debug("Skipping email dispatch: no email found for userId={}", notification.getUserId());
                return;
            }
            if (isSyntheticTestDomain(recipientEmail)) {
                log.debug("Skipping Brevo email dispatch for synthetic domain [email={}]", recipientEmail);
                return;
            }

            Map<String, Object> body = Map.of(
                    "sender", Map.of("name", senderName, "email", senderEmail),
                    "to", List.of(Map.of("email", recipientEmail)),
                    "subject", "[Vigilia] " + notification.getTitle(),
                    "htmlContent", buildHtml(notification)
            );

            brevoRestClient.post()
                    .uri(apiUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("api-key", apiKey)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Brevo email dispatched [notificationId={}, userId={}, category={}]",
                    notification.getId(), notification.getUserId(), notification.getCategory());
        } catch (Exception ex) {
            log.warn("Brevo email dispatch failed [notificationId={}]: {}",
                    notification.getId(), ex.getMessage());
        }
    }

    private String resolveRecipientEmail(UUID userId, Map<String, Object> metadata) {
        if (metadata != null && metadata.get("email") instanceof String e && !e.isBlank()) {
            return e.trim();
        }
        if (customerRestClient == null || userId == null) {
            return null;
        }
        try {
            JsonNode res = customerRestClient.get()
                    .uri("/internal/customers/{userId}", userId.toString())
                    .retrieve()
                    .body(JsonNode.class);
            if (res != null) {
                String email = res.path("data").path("email").asText("");
                if (!email.isBlank()) return email.trim();
            }
        } catch (Exception ex) {
            log.debug("Could not resolve customer email from customer-service [userId={}]: {}", userId, ex.getMessage());
        }
        return null;
    }

    private boolean isSyntheticTestDomain(String email) {
        String lower = email.toLowerCase();
        return lower.endsWith("@example.com")
                || lower.endsWith("@example.org")
                || lower.endsWith("@localhost");
    }

    private String buildHtml(NotificationEntity n) {
        String badgeColor = switch (n.getSeverity()) {
            case "CRITICAL" -> "#dc2626";
            case "WARNING" -> "#d97706";
            case "SUCCESS" -> "#059669";
            default -> "#2563eb";
        };
        return """
                <div style="font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif; background:#f8fafc; padding:32px 16px;">
                  <div style="max-width:540px; margin:0 auto; background:#ffffff; border:1px solid #e2e8f0; border-radius:16px; overflow:hidden;">
                    <div style="background:#0f172a; padding:20px 24px; color:#ffffff; font-weight:700; font-size:18px;">
                      Vigilia <span style="color:#d97236; font-weight:600; font-size:13px; margin-left:8px;">Security &amp; Alerts</span>
                    </div>
                    <div style="padding:24px;">
                      <span style="display:inline-block; padding:3px 10px; border-radius:999px; font-size:11px; font-weight:700; color:#ffffff; background:%s; text-transform:uppercase;">%s</span>
                      <h2 style="margin:14px 0 8px; color:#0f172a; font-size:18px;">%s</h2>
                      <p style="margin:0 0 18px; color:#475569; font-size:14px; line-height:1.6;">%s</p>
                      <p style="margin:0; color:#94a3b8; font-size:12px;">You are receiving this alert because email notifications are enabled in your Vigilia account.</p>
                    </div>
                  </div>
                </div>
                """.formatted(badgeColor, escapeHtml(n.getCategory()), escapeHtml(n.getTitle()), escapeHtml(n.getMessage()));
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}

