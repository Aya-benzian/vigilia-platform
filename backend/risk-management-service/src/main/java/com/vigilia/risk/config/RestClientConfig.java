package com.vigilia.risk.config;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP client configuration for calls to the ML Serving FastAPI service.
 *
 * Timeouts are read from application.properties:
 *   risk.ml.timeout-ms         → read timeout (default 1500 ms)
 *   risk.ml.connect-timeout-ms → connect timeout (default 1000 ms)
 *
 * IMPORTANT: Both timeouts are wired into the ClientHttpRequestFactory so that
 * a slow or unresponsive ML server cannot block a thread indefinitely.
 * On any timeout, RestClientException is caught by MlPredictionClient and
 * triggers a safe PENDING_REVIEW fallback — never APPROVED.
 */
@Configuration
public class RestClientConfig {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Value("${risk.ml.timeout-ms:1500}")
    private int mlReadTimeoutMs;

    @Value("${risk.ml.connect-timeout-ms:1000}")
    private int mlConnectTimeoutMs;

    /**
     * Creates a ClientHttpRequestFactory with explicit connect and read timeouts.
     * SimpleClientHttpRequestFactory is available in all Spring versions without
     * additional dependencies.
     */
    @Bean
    public ClientHttpRequestFactory mlClientHttpRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(mlConnectTimeoutMs);
        factory.setReadTimeout(mlReadTimeoutMs);
        return factory;
    }

    @Bean
    public ClientHttpRequestInterceptor correlationIdInterceptor() {
        return (request, body, execution) -> {
            String correlationId = MDC.get(MDC_KEY);
            if (correlationId != null && !correlationId.isBlank()) {
                request.getHeaders().set(CORRELATION_ID_HEADER, correlationId);
            }
            return execution.execute(request, body);
        };
    }

    /**
     * RestClient.Builder pre-configured with:
     * - ML HTTP timeouts (connect + read via SimpleClientHttpRequestFactory)
     * - X-Correlation-Id forwarding interceptor
     *
     * This builder is injected into MlPredictionClient.
     */
    @Bean
    public RestClient.Builder restClientBuilder(ClientHttpRequestFactory mlClientHttpRequestFactory,
                                                ClientHttpRequestInterceptor correlationIdInterceptor) {
        return RestClient.builder()
                .requestFactory(mlClientHttpRequestFactory)
                .requestInterceptor(correlationIdInterceptor);
    }
}
