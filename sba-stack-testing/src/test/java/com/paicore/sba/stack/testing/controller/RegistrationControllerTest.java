package com.paicore.sba.stack.testing.controller;

import com.paicore.sba.stack.dto.RegistrationRequest;
import com.paicore.sba.stack.testing.TestFixtures;
import com.paicore.sba.stack.testing.controller.dto.HttpResult;
import com.paicore.sba.stack.testing.service.RegistrationService;
import com.paicore.sba.stack.utils.SdkConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Controller integration tests — exercise JSON decoding + service call +
 * response shaping without standing up Netty or a real HTTP socket.
 */
class RegistrationControllerTest {

    private RegistrationService service;
    private RegistrationController controller;

    @BeforeEach
    void setUp() {
        service = new RegistrationService();
        controller = new RegistrationController(service);
    }

    @Test
    @DisplayName("POST with wrapped RegistrationRequest body returns 201 and registers the UE")
    void registerWithWrappedRequest() {
        byte[] body = SdkConverter.valueAsString(TestFixtures.defaultRegistrationRequest())
                .getBytes(StandardCharsets.UTF_8);

        HttpResult result = controller.register(TestFixtures.DEFAULT_SUPI, body
        );

        assertThat(result.status()).isEqualTo(201);
        assertThat(new String(result.body(), StandardCharsets.UTF_8))
                .contains("\"sessionId\"").contains("\"supi\":\"" + TestFixtures.DEFAULT_SUPI + "\"");
        assertThat(service.isRegistered(TestFixtures.DEFAULT_SUPI)).isTrue();
    }

    @Test
    @DisplayName("POST with bare UeSmsContextData body (TS29.540 example shape) also works")
    void registerWithBareContextBody() {
        byte[] body = SdkConverter.valueAsString(TestFixtures.defaultUeContext())
                .getBytes(StandardCharsets.UTF_8);

        HttpResult result = controller.register(TestFixtures.DEFAULT_SUPI, body
        );

        assertThat(result.status()).isEqualTo(201);
        assertThat(service.registeredCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("garbage JSON body returns 400 + problem document, no registration")
    void rejectsUnparseableBody() {
        HttpResult result = controller.register(TestFixtures.DEFAULT_SUPI,
                "not-a-json".getBytes(StandardCharsets.UTF_8)
        );

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.headers()).containsEntry("content-type", "application/problem+json");
        assertThat(service.registeredCount()).isZero();
    }

    @Test
    @DisplayName("SUPI mismatch between URL and body is rejected by the service")
    void rejectsSupiMismatch() {
        RegistrationRequest req = RegistrationRequest.builder()
                .correlationId("corr-x")
                .ueContext(TestFixtures.defaultUeContext()) // SUPI = imsi-...001
                .build();
        byte[] body = SdkConverter.valueAsString(req).getBytes(StandardCharsets.UTF_8);

        HttpResult result = controller.register("imsi-460000000000999", body
        );

        assertThat(result.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("deregister returns 200 + JSON when UE was registered, 404 otherwise")
    void deregisterFlow() {
        byte[] body = SdkConverter.valueAsString(TestFixtures.defaultRegistrationRequest())
                .getBytes(StandardCharsets.UTF_8);
        controller.register(TestFixtures.DEFAULT_SUPI, body
        );

        HttpResult ok = controller.deregister(TestFixtures.DEFAULT_SUPI);
        HttpResult missing = controller.deregister("imsi-460000000000404");

        assertThat(ok.status()).isEqualTo(200);
        assertThat(missing.status()).isEqualTo(404);
        assertThat(service.isRegistered(TestFixtures.DEFAULT_SUPI)).isFalse();
    }
}
