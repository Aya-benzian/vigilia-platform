package com.vigilia.customer.security;

import com.vigilia.customer.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Security helper to extract and validate the authenticated user identity
 * injected by the API Gateway after JWT verification (Section 17.1 & anti-spoofing).
 */
@Component
public class SecurityContextHelper {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLE_HEADER = "X-User-Role";
    public static final String USER_ROLES_HEADER = "X-User-Roles";
    public static final String USER_EMAIL_HEADER = "X-User-Email";

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

    public String resolveUserRole(HttpServletRequest request) {
        return request != null ? request.getHeader(USER_ROLE_HEADER) : null;
    }

    public String resolveUserRoles(HttpServletRequest request) {
        return request != null ? request.getHeader(USER_ROLES_HEADER) : null;
    }

    public String resolveUserEmail(HttpServletRequest request) {
        return request != null ? request.getHeader(USER_EMAIL_HEADER) : null;
    }

    /**
     * Checks if the caller has a privileged role (ANALYST or ADMIN).
     * Rejects direct untrusted client headers if not verified by API Gateway.
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
