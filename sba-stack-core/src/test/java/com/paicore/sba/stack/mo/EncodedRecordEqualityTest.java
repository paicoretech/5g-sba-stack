package com.paicore.sba.stack.mo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the byte[]-aware overrides on {@link MultipartRelatedEncoder.Encoded}
 * (and indirectly on the package-private {@code Part} record used by the
 * decoder) so JaCoCo registers them as exercised.
 */
class EncodedRecordEqualityTest {

    @Test
    @DisplayName("Encoded equality compares contentType + body bytes")
    void encodedEqualityIsContentBased() {
        MultipartRelatedEncoder.Encoded a = new MultipartRelatedEncoder.Encoded(
                "multipart/related", new byte[]{1, 2, 3});
        MultipartRelatedEncoder.Encoded b = new MultipartRelatedEncoder.Encoded(
                "multipart/related", new byte[]{1, 2, 3});
        MultipartRelatedEncoder.Encoded c = new MultipartRelatedEncoder.Encoded(
                "multipart/related", new byte[]{9, 9, 9});

        assertThat(a)
                .isEqualTo(b)
                .isNotEqualTo(c)
                .isNotEqualTo("nope")
                .hasSameHashCodeAs(b);
        assertThat(a.toString()).contains("Encoded", "multipart/related", "body=[1, 2, 3]");
    }
}
