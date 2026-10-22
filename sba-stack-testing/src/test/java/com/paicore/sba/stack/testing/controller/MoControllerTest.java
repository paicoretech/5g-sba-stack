package com.paicore.sba.stack.testing.controller;

import com.paicore.sba.stack.mo.MultipartRelatedEncoder;
import com.paicore.sba.stack.testing.TestFixtures;
import com.paicore.sba.stack.testing.controller.dto.HttpResult;
import com.paicore.sba.stack.testing.fixtures.TpduFixture;
import com.paicore.sba.stack.testing.service.MoService;
import com.paicore.sba.stack.testing.service.RegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Controller integration tests — exercise multipart/related decoding +
 * TPDU decoding via the stack libs and confirm the controller hands the
 * service typed objects (no HTTP, no Netty).
 */
class MoControllerTest {

    private RegistrationService registry;
    private MoService moService;
    private MoController controller;

    @BeforeEach
    void setUp() {
        registry = new RegistrationService();
        moService = new MoService(registry);
        controller = new MoController(moService);
        registry.register(TestFixtures.DEFAULT_SUPI, TestFixtures.defaultUeContext());
    }

    @Test
    @DisplayName("multipart body with UCS-2 TPDU is decoded and the service accepts the MO")
    void happyPathUcs2() {
        byte[] tpdu = TpduFixture.ucs2Submit("+50212345678", "Hello world");
        MultipartRelatedEncoder.Encoded encoded = MultipartRelatedEncoder.encode(
                TestFixtures.smsRecordFor("rec-mo-ucs2"), tpdu);

        HttpResult result = controller.sendsms(TestFixtures.DEFAULT_SUPI,
                encoded.body(), Map.of("content-type", encoded.contentType()));

        assertThat(result.status()).isEqualTo(202);
        assertThat(new String(result.body(), StandardCharsets.UTF_8))
                .contains("\"decodedMessage\":\"Hello world\"")
                .contains("\"tpduType\":\"SMS_SUBMIT\"");
        assertThat(moService.captures()).hasSize(1);
        assertThat(moService.captures().getFirst().tpdu().decodedMessage()).isEqualTo("Hello world");
    }

    @Test
    @DisplayName("GSM-7 TPDU is also decoded and routed to the service")
    void gsm7Payload() {
        byte[] tpdu = TpduFixture.gsm7Submit("+50212345678", "Hello world");
        MultipartRelatedEncoder.Encoded encoded = MultipartRelatedEncoder.encode(
                TestFixtures.smsRecordFor("rec-mo-gsm7"), tpdu);

        HttpResult result = controller.sendsms(TestFixtures.DEFAULT_SUPI,
                encoded.body(), Map.of("content-type", encoded.contentType()));

        assertThat(result.status()).isEqualTo(202);
        assertThat(moService.captures().getFirst().tpdu().dataCodingScheme()).isEqualTo(0x00);
    }

    @Test
    @DisplayName("malformed multipart body returns 400 and the service never sees it")
    void rejectsMalformedMultipart() {
        HttpResult result = controller.sendsms(TestFixtures.DEFAULT_SUPI,
                "garbage".getBytes(StandardCharsets.UTF_8),
                Map.of("content-type", "multipart/related; boundary=xxx"));

        assertThat(result.status()).isEqualTo(400);
        assertThat(moService.captures()).isEmpty();
    }

    @Test
    @DisplayName("multipart with a corrupted TPDU part returns 400 from the codec")
    void rejectsCorruptedTpdu() {
        // Build a multipart whose binary part is non-decodable.
        byte[] garbage = new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};
        MultipartRelatedEncoder.Encoded encoded = MultipartRelatedEncoder.encode(
                TestFixtures.smsRecordFor("rec-bad"), garbage);

        HttpResult result = controller.sendsms(TestFixtures.DEFAULT_SUPI,
                encoded.body(), Map.of("content-type", encoded.contentType()));

        assertThat(result.status()).isEqualTo(400);
        assertThat(moService.captures()).isEmpty();
    }

    @Test
    @DisplayName("unregistered UE produces 404 from the service via the controller")
    void rejectsUnregisteredUe() {
        registry.clear();
        byte[] tpdu = TpduFixture.ucs2Submit("+50212345678", "Hi");
        MultipartRelatedEncoder.Encoded encoded = MultipartRelatedEncoder.encode(
                TestFixtures.smsRecordFor("rec-unreg"), tpdu);

        HttpResult result = controller.sendsms(TestFixtures.DEFAULT_SUPI,
                encoded.body(), Map.of("content-type", encoded.contentType()));

        assertThat(result.status()).isEqualTo(404);
    }
}
