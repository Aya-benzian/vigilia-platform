package com.vigilia.auth.service;

import com.vigilia.auth.dto.AuthResponse;
import com.vigilia.auth.dto.LoginRequest;
import com.vigilia.auth.dto.RefreshTokenRequest;
import com.vigilia.auth.dto.RegisterRequest;
import com.vigilia.auth.dto.UserSummaryDto;

import java.util.UUID;

public interface AuthService {

    AuthResponse register(RegisterRequest request, String ipAddress, String traceId);

    AuthResponse login(LoginRequest request, String ipAddress, String traceId);

    AuthResponse refreshToken(RefreshTokenRequest request, String ipAddress, String traceId);

    void logout(String rawRefreshToken, String traceId);

    UserSummaryDto getCurrentUser(UUID userId);
}
