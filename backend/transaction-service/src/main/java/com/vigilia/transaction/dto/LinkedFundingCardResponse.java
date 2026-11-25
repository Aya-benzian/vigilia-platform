package com.vigilia.transaction.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO representing a customer's linked external funding card.
 */
public record LinkedFundingCardResponse(
        UUID id,
        String brand,
        String last4,
        String cardholderName,
        String exp,
        boolean isDefault,
        Instant createdAt
) {
}

