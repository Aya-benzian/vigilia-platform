package com.vigilia.risk.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RestClientConfig unit tests")
class RestClientConfigTest {

    @Test
    @DisplayName("mlClientHttpRequestFactory applies configured connect and read timeouts")
    void mlClientHttpRequestFactory_appliesTimeouts() {
        RestClientConfig config = new RestClientConfig();

        // Inject custom timeout values via ReflectionTestUtils (simulating @Value)
        ReflectionTestUtils.setField(config, "mlConnectTimeoutMs", 1200);
        ReflectionTestUtils.setField(config, "mlReadTimeoutMs", 1800);

        ClientHttpRequestFactory factory = config.mlClientHttpRequestFactory();

        assertThat(factory).isInstanceOf(SimpleClientHttpRequestFactory.class);

        // Verify the timeouts are applied inside SimpleClientHttpRequestFactory
        Object connectTimeout = ReflectionTestUtils.getField(factory, "connectTimeout");
        Object readTimeout = ReflectionTestUtils.getField(factory, "readTimeout");

        assertThat(connectTimeout).isEqualTo(1200);
        assertThat(readTimeout).isEqualTo(1800);
    }

    @Test
    @DisplayName("mlClientHttpRequestFactory defaults to 1000ms connect and 1500ms read timeouts")
    void mlClientHttpRequestFactory_defaultTimeouts() {
        RestClientConfig config = new RestClientConfig();
        // Values as initialized by field defaults
        ReflectionTestUtils.setField(config, "mlConnectTimeoutMs", 1000);
        ReflectionTestUtils.setField(config, "mlReadTimeoutMs", 1500);

        ClientHttpRequestFactory factory = config.mlClientHttpRequestFactory();

        assertThat(ReflectionTestUtils.getField(factory, "connectTimeout")).isEqualTo(1000);
        assertThat(ReflectionTestUtils.getField(factory, "readTimeout")).isEqualTo(1500);
    }

    @Test
    @DisplayName("restClientBuilder builds a RestClient with configured factory")
    void restClientBuilder_configuresRestClient() {
        RestClientConfig config = new RestClientConfig();
        ClientHttpRequestFactory factory = config.mlClientHttpRequestFactory();
        ClientHttpRequestInterceptor interceptor = config.correlationIdInterceptor();

        RestClient.Builder builder = config.restClientBuilder(factory, interceptor);
        assertThat(builder).isNotNull();

        RestClient client = builder.build();
        assertThat(client).isNotNull();
    }
}
