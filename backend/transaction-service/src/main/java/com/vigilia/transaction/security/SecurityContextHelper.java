package com.vigilia.transaction.security;

import com.vigilia.transaction.exception.ForbiddenException;
import com.vigilia.transaction.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Security helper to extract and validate the authenticated user identity
 * and roles injected by the API Gateway after JWT verification.
 */
@Component
public class SecurityContextHelper {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLE_HEADER = "X-User-Role";
    public static final String USER_ROLES_HEADER = "X-User-Roles";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /**
     * Resolves the authenticated user ID from the request header.
     * Throws UnauthorizedException if the header is absent or invalid.
     */
    public UUID requireCurrentUserId(HttpServletRequest request) {
        if (request == null) {
            throw new UnauthorizedException("Requête HTTP manquante");
        }

        String userIdHeader = request.getHeader(USER_ID_HEADER);
        if (userIdHeader == null || userIdHeader.trim().isEmpty()) {
            throw new UnauthorizedException("Identité utilisateur non authentifiée (en-tête " + USER_ID_HEADER + " absent)");
        }

        try {
            return UUID.fromString(userIdHeader.trim());
        } catch (IllegalArgumentException e) {
            throw new UnauthorizedException("Identifiant utilisateur non valide : format UUID attendu");
        }
    }

    /**
     * Optional resolution of current user ID (returns null if not authenticated).
     */
    public UUID resolveCurrentUserId(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String userIdHeader = request.getHeader(USER_ID_HEADER);
        if (userIdHeader == null || userIdHeader.trim().isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(userIdHeader.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Checks if the caller has a privileged role (ANALYST or ADMIN).
     */
    public boolean hasPrivilegedRole(HttpServletRequest request) {
        if (request == null) {
            return false;
        }

        String singleRole = request.getHeader(USER_ROLE_HEADER);
        if (isPrivileged(singleRole)) {
            return true;
        }

        String multiRoles = request.getHeader(USER_ROLES_HEADER);
        if (multiRoles != null && !multiRoles.isBlank()) {
            String[] roles = multiRoles.split(",");
            for (String r : roles) {
                if (isPrivileged(r.trim())) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Asserts that the authenticated user either owns the resource (matches customerId)
     * or possesses a privileged role (ANALYST / ADMIN).
     */
    public void assertOwnershipOrPrivileged(HttpServletRequest request, UUID customerId) {
        if (hasPrivilegedRole(request)) {
            return;
        }
        UUID currentUserId = requireCurrentUserId(request);
        if (customerId != null && !currentUserId.equals(customerId)) {
            throw new ForbiddenException("Accès non autorisé : vous ne pouvez pas accéder aux transactions d'un tiers");
        }
    }

    private boolean isPrivileged(String role) {
        if (role == null) {
            return false;
        }
        return "ANALYST".equalsIgnoreCase(role)
                || "ROLE_ANALYST".equalsIgnoreCase(role)
                || "ADMIN".equalsIgnoreCase(role)
                || "ROLE_ADMIN".equalsIgnoreCase(role);
    }
}
