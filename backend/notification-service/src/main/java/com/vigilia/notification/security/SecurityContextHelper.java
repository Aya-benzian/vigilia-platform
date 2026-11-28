package com.vigilia.notification.security;

import com.vigilia.notification.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Extracts the authenticated user ID injected by the API Gateway (X-User-Id).
 */
@Component
public class SecurityContextHelper {

    public static final String USER_ID_HEADER = "X-User-Id";

    public UUID requireCurrentUserId(HttpServletRequest request) {
        if (request == null) {
            throw new UnauthorizedException("Missing HTTP request");
        }
        String userIdHeader = request.getHeader(USER_ID_HEADER);
        if (userIdHeader == null || userIdHeader.trim().isEmpty()) {
            throw new UnauthorizedException("Authenticated user identity missing (" + USER_ID_HEADER + " header required)");
        }
        try {
            return UUID.fromString(userIdHeader.trim());
        } catch (IllegalArgumentException e) {
            throw new UnauthorizedException("Invalid user identifier format: UUID expected");
        }
    }
}

