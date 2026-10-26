package com.vigilia.risk.service;

import com.vigilia.risk.dto.RiskEvaluationRequest;
import com.vigilia.risk.dto.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MlPredictionClient unit tests")
class MlPredictionClientTest {

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock private RestClient.RequestBodySpec requestBodySpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    private MlPredictionClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.build()).thenReturn(restClient);

        client = new MlPredictionClient(builder);
        ReflectionTestUtils.setField(client, "mlBaseUrl", "http://ml-serving:8000");
    }

    private RiskEvaluationRequest buildRequest() {
        RiskEvaluationRequest req = new RiskEvaluationRequest();
        req.setTransactionId(UUID.randomUUID());
        req.setCustomerId(UUID.randomUUID());
        req.setTransactionType(TransactionType.PURCHASE);
        req.setAmount(new BigDecimal("100.00"));
        req.setCurrency("EUR");
        req.setTimestamp(Instant.now());
        return req;
    }

    @Test
    @DisplayName("predict: on socket timeout (ResourceAccessException), returns null safely")
    void predict_timeout_returnsNullSafely() {
        RiskEvaluationRequest req = buildRequest();

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("http://ml-serving:8000/predict/purchase")).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(MlPredictionClient.MlPredictionResponse.class))
                .thenThrow(new ResourceAccessException("Read timed out", new SocketTimeoutException("Read timed out")));

        MlPredictionClient.MlPredictionResponse response = client.predict(req);

        // Crucial safety guarantee: failure returns null, never an approving response
        assertThat(response).isNull();
    }

    @Test
    @DisplayName("E4: predict with TRANSFER skips ML call completely and returns null")
    void predict_transfer_skipsMlCallAndReturnsNull() {
        RiskEvaluationRequest req = buildRequest();
        req.setTransactionType(TransactionType.TRANSFER);

        MlPredictionClient.MlPredictionResponse response = client.predict(req);

        assertThat(response).isNull();
        verify(restClient, never()).post();
    }

    @Test
    @DisplayName("E5: predict with PURCHASE parses inference_ms as Integer")
    void predict_purchase_success_mapsInferenceMsAsInteger() {
        RiskEvaluationRequest req = buildRequest();

        MlPredictionClient.MlPredictionResponse expected = new MlPredictionClient.MlPredictionResponse();
        expected.setTransaction_id(req.getTransactionId().toString());
        expected.setPrediction("LEGITIMATE");
        expected.setFraud_probability(0.05);
        expected.setUncertainty(0.02);
        expected.setInference_ms(42);

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("http://ml-serving:8000/predict/purchase")).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(MlPredictionClient.MlPredictionResponse.class)).thenReturn(expected);

        MlPredictionClient.MlPredictionResponse response = client.predict(req);

        assertThat(response).isNotNull();
        assertThat(response.getInference_ms()).isEqualTo(42);
        assertThat(response.getInference_ms()).isInstanceOf(Integer.class);
    }
}
