package com.paicore.sba.stack.api.n21;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class N21PathsTest {

    @Test
    void substitutesTheUeIdPlaceholder() {
        assertThat(N21Paths.resolve(N21Paths.SMSF_3GPP_ACCESS_REGISTRATION, "imsi-001010000000001"))
                .isEqualTo("/nudm-uecm/v1/imsi-001010000000001/registrations/smsf-3gpp-access");
    }

    @Test
    void substitutesTheSupiAlias() {
        assertThat(N21Paths.resolve("/nudm-uecm/v1/{supi}/registrations/smsf-3gpp-access", "imsi-1"))
                .isEqualTo("/nudm-uecm/v1/imsi-1/registrations/smsf-3gpp-access");
    }

    @Test
    void urlEncodesTheUeId() {
        assertThat(N21Paths.resolve("/x/{ueId}", "imsi 1/2")).isEqualTo("/x/imsi+1%2F2");
    }

    @Test
    void appendsTheUeIdWhenTheTemplateHasNoPlaceholder() {
        assertThat(N21Paths.resolve("/nudm-uecm/v1", "imsi-1")).isEqualTo("/nudm-uecm/v1/imsi-1");
        assertThat(N21Paths.resolve("/nudm-uecm/v1/", "imsi-1")).isEqualTo("/nudm-uecm/v1/imsi-1");
    }

    @Test
    void handlesMissingTemplateOrUeId() {
        assertThat(N21Paths.resolve(null, "imsi-1")).isEqualTo("/imsi-1");
        assertThat(N21Paths.resolve("  ", "imsi-1")).isEqualTo("/imsi-1");
        assertThat(N21Paths.resolve("/x/{ueId}", null)).isEqualTo("/x/");
    }

    @Test
    void buildsTheTemplateFromTheServiceAndVersionAdvertisedByTheNrf() {
        assertThat(N21Paths.resourceTemplate("nudm-uecm", "v2"))
                .isEqualTo("/nudm-uecm/v2/{ueId}/registrations/smsf-3gpp-access");
        assertThat(N21Paths.resolve(N21Paths.resourceTemplate("nudm-uecm", "v2"), "imsi-1"))
                .isEqualTo("/nudm-uecm/v2/imsi-1/registrations/smsf-3gpp-access");
    }

    @Test
    void theTemplateFallsBackToTheSpecDefaultsWhenTheNrfSaysNothing() {
        assertThat(N21Paths.resourceTemplate(null, null))
                .isEqualTo(N21Paths.SMSF_3GPP_ACCESS_REGISTRATION);
        assertThat(N21Paths.resourceTemplate("  ", "  "))
                .isEqualTo(N21Paths.SMSF_3GPP_ACCESS_REGISTRATION);
        assertThat(N21Paths.resourceTemplate("nudm-uecm", null))
                .isEqualTo("/nudm-uecm/v1/{ueId}/registrations/smsf-3gpp-access");
    }

    @Test
    void detectsWhetherATemplateAddressesASubscriber() {
        assertThat(N21Paths.hasPlaceholder(N21Paths.SMSF_3GPP_ACCESS_REGISTRATION)).isTrue();
        assertThat(N21Paths.hasPlaceholder("/x/{supi}")).isTrue();
        assertThat(N21Paths.hasPlaceholder("/x")).isFalse();
        assertThat(N21Paths.hasPlaceholder(null)).isFalse();
    }
}
