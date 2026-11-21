package com.vigilia.transaction.controller;

import com.vigilia.transaction.dto.ApiResponse;
import com.vigilia.transaction.dto.OutboxStatusResponse;
import com.vigilia.transaction.repository.OutboxEventRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes a live health snapshot of the transactional outbox relay for the monitor dashboard.
 * No auth required — read-only operational metric, proxied through the API gateway.
 *
 * GET /api/v1/transactions/outbox/status
 */
@RestController
@RequestMapping("/api/v1/transactions/outbox")
public class OutboxStatusController {

    private final OutboxEventRepository outboxRepo;

    public OutboxStatusController(OutboxEventRepository outboxRepo) {
        this.outboxRepo = outboxRepo;
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<OutboxStatusResponse>> getOutboxStatus() {
        OutboxStatusResponse status = new OutboxStatusResponse();

        long pending   = outboxRepo.countByStatus("PENDING");
        long published = outboxRepo.countByStatus("PUBLISHED");
        long failed    = outboxRepo.countByStatus("FAILED");

        status.setPending(pending);
        status.setPublished(published);
        status.setFailed(failed);
        status.setHealthy(pending == 0 && failed == 0);

        return ResponseEntity.ok(ApiResponse.of(status));
    }
}

