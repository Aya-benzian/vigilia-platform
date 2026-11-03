package com.vigilia.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilia.auth.dto.AuthResponse;
import com.vigilia.auth.dto.LoginRequest;
import com.vigilia.auth.dto.RegisterRequest;
import com.vigilia.auth.exception.AuthenticationFailedException;
import com.vigilia.auth.security.JwtTokenProvider;
import com.vigilia.auth.security.RsaKeyProvider;
import com.vigilia.auth.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @Autowired
    private RsaKeyProvider rsaKeyProvider;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("POST /api/v1/auth/register - Succès 201 avec rôle CUSTOMER")
    void testRegisterEndpointSuccess() throws Exception {
        RegisterRequest request = new RegisterRequest("sara@example.com", "S3cure-Passphrase!");

        AuthResponse authResponse = new AuthResponse(
                "mocked-access-token",
                "mocked-refresh-token",
                900L,
                UUID.randomUUID(),
                "sara@example.com",
                "CUSTOMER"
        );

        when(authService.register(any(), any(), any())).thenReturn(authResponse);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", "test-corr-id")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accessToken").value("mocked-access-token"))
                .andExpect(jsonPath("$.data.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.data.email").value("sara@example.com"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/register - Refus 403 en cas de tentative d'attribution du rôle ADMIN")
    void testRegisterRejectAdminRole() throws Exception {
        RegisterRequest request = new RegisterRequest("admin@example.com", "S3cure-Passphrase!");
        request.setRole("ADMIN");

        when(authService.register(any(), any(), any()))
                .thenThrow(new AccessDeniedException("L'auto-attribution des rôles privilégiés est interdite"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/register - Erreur 400 sur format d'email invalide")
    void testRegisterInvalidEmail() throws Exception {
        RegisterRequest request = new RegisterRequest("invalid-email-format", "S3cure-Passphrase!");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/login - Succès 200")
    void testLoginSuccess() throws Exception {
        LoginRequest request = new LoginRequest("sara@example.com", "S3cure-Passphrase!");

        AuthResponse authResponse = new AuthResponse(
                "mocked-access-token",
                "mocked-refresh-token",
                900L,
                UUID.randomUUID(),
                "sara@example.com",
                "CUSTOMER"
        );

        when(authService.login(any(), any(), any())).thenReturn(authResponse);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("mocked-access-token"))
                .andExpect(jsonPath("$.data.refreshToken").value("mocked-refresh-token"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/login - Échec 401 en cas d'identifiants incorrects (erreur générique)")
    void testLoginBadCredentials() throws Exception {
        LoginRequest request = new LoginRequest("sara@example.com", "WrongPassword!");

        when(authService.login(any(), any(), any()))
                .thenThrow(new AuthenticationFailedException("Identifiants invalides"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Identifiants invalides"));
    }

    @Test
    @DisplayName("GET /.well-known/jwks.json - Exposition publique des clés JWKS RS256")
    void testJwksEndpoint() throws Exception {
        mockMvc.perform(get("/.well-known/jwks.json")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys").isArray())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].use").value("sig"))
                .andExpect(jsonPath("$.keys[0].kid").value("vigilia-key-1"))
                .andExpect(jsonPath("$.keys[0].n").isNotEmpty())
                .andExpect(jsonPath("$.keys[0].e").isNotEmpty());
    }

    @Test
    @DisplayName("GET /api/v1/auth/.well-known/jwks.json - Route alternative JWKS")
    void testAlternativeJwksEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/auth/.well-known/jwks.json")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kid").value("vigilia-key-1"));
    }
}
