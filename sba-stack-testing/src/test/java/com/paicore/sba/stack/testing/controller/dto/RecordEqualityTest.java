package com.paicore.sba.stack.testing.controller.dto;

import com.paicore.sba.stack.openapi.model.SmsRecordData;
import com.paicore.sba.stack.testing.mock.MockSbaCoreServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the byte[]-aware equals/hashCode/toString methods on the records
 * the testing kit exposes — {@link DecodedTpdu}, {@link HttpResult}, and the
 * Mo/MtCapture snapshots in {@link MockSbaCoreServer}.
 */
class RecordEqualityTest {

    @Test
    @DisplayName("DecodedTpdu equality is content-based (byte[] compared with Arrays.equals)")
    void decodedTpduEqualityIsContentBased() {
        DecodedTpdu a = new DecodedTpdu(new byte[]{1, 2, 3}, "SMS_SUBMIT", 0, "+1", "Hi", null);
        DecodedTpdu b = new DecodedTpdu(new byte[]{1, 2, 3}, "SMS_SUBMIT", 0, "+1", "Hi", null);
        DecodedTpdu c = new DecodedTpdu(new byte[]{9, 9, 9}, "SMS_SUBMIT", 0, "+1", "Hi", null);

        assertThat(a)
                .isEqualTo(b)
                .isNotEqualTo(c)
                .isNotEqualTo("not-a-tpdu")
                .hasSameHashCodeAs(b);
        assertThat(a.toString())
                .contains("DecodedTpdu", "SMS_SUBMIT", "rawBytes=[1, 2, 3]");
    }

    @Test
    @DisplayName("HttpResult equality is content-based")
    void httpResultEqualityIsContentBased() {
        HttpResult a = HttpResult.ok("hello");
        HttpResult b = HttpResult.ok("hello");
        HttpResult c = HttpResult.ok("world");

        assertThat(a)
                .isEqualTo(b)
                .isNotEqualTo(c)
                .isNotEqualTo("not-a-result")
                .hasSameHashCodeAs(b);
        assertThat(a.toString()).contains("HttpResult", "status=200");
    }

    @Test
    @DisplayName("MoCapture equality compares byte[] tpdu via Arrays.equals")
    void moCaptureEqualityIsContentBased() {
        SmsRecordData rec = new SmsRecordData().smsRecordId("r-1");
        MockSbaCoreServer.MoCapture a =
                new MockSbaCoreServer.MoCapture("supi-1", rec, new byte[]{1, 2}, null);
        MockSbaCoreServer.MoCapture b =
                new MockSbaCoreServer.MoCapture("supi-1", rec, new byte[]{1, 2}, null);
        MockSbaCoreServer.MoCapture c =
                new MockSbaCoreServer.MoCapture("supi-2", rec, new byte[]{1, 2}, null);

        assertThat(a)
                .isEqualTo(b)
                .isNotEqualTo(c)
                .isNotEqualTo("nope")
                .hasSameHashCodeAs(b);
        assertThat(a.toString()).contains("MoCapture", "supi-1", "tpdu=[1, 2]");
    }

    @Test
    @DisplayName("MtCapture equality compares byte[] tpdu via Arrays.equals")
    void mtCaptureEqualityIsContentBased() {
        SmsRecordData rec = new SmsRecordData().smsRecordId("r-1");
        MockSbaCoreServer.MtCapture a =
                new MockSbaCoreServer.MtCapture("supi-1", rec, new byte[]{1, 2}, null);
        MockSbaCoreServer.MtCapture b =
                new MockSbaCoreServer.MtCapture("supi-1", rec, new byte[]{1, 2}, null);
        MockSbaCoreServer.MtCapture c =
                new MockSbaCoreServer.MtCapture("supi-1", rec, new byte[]{3, 4}, null);

        assertThat(a)
                .isEqualTo(b)
                .isNotEqualTo(c)
                .hasSameHashCodeAs(b);
        assertThat(a.toString()).contains("MtCapture", "tpdu=[1, 2]");
    }
}
