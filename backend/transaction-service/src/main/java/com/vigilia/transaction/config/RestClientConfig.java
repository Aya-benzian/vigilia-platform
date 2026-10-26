package com.vigilia.transaction.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

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

    @Bean
    public RestClient.Builder restClientBuilder(ClientHttpRequestInterceptor correlationIdInterceptor) {
        return RestClient.builder().requestInterceptor(correlationIdInterceptor);
    }
}
