package com.paicore.sba.stack.api.nnrf.disc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NfDiscoveryQueryTest {

    @Test
    void rendersOnlyTheParametersThatAreSet() {
        String query = NfDiscoveryQuery.builder()
                .targetNfType("UDM")
                .requesterNfType("SMSF")
                .build()
                .toQueryString();

        assertThat(query).isEqualTo("target-nf-type=UDM&requester-nf-type=SMSF");
    }

    @Test
    void rendersSubscriberSelectorsAndServiceNames() {
        String query = NfDiscoveryQuery.builder()
                .targetNfType("UDM")
                .requesterNfType("SMSF")
                .requesterNfInstanceId("11111111-2222-3333-4444-555555555555")
                .serviceName("nudm-uecm")
                .supi("imsi-001010000000001")
                .routingIndicator("1234")
                .groupId("udm-group-1")
                .groupId("udm-group-2")
                .preferredLocality("edge-1")
                .limit(5)
                .build()
                .toQueryString();

        assertThat(query)
                .contains("service-names=nudm-uecm")
                .contains("supi=imsi-001010000000001")
                .contains("routing-indicator=1234")
                .contains("group-id-list=udm-group-1%2Cudm-group-2")
                .contains("requester-nf-instance-id=11111111-2222-3333-4444-555555555555")
                .contains("preferred-locality=edge-1")
                .contains("limit=5");
    }

    @Test
    void urlEncodesValues() {
        String query = NfDiscoveryQuery.builder()
                .targetNfType("UDM")
                .gpsi("msisdn-34 600 000")
                .build()
                .toQueryString();

        assertThat(query).contains("gpsi=msisdn-34+600+000");
    }

    @Test
    void skipsBlankValuesIncludingInsideLists() {
        String query = NfDiscoveryQuery.builder()
                .targetNfType("UDM")
                .supi("  ")
                .serviceName(" ")
                .groupId("udm-group-1")
                .groupId("  ")
                .build()
                .toQueryString();

        assertThat(query).isEqualTo("target-nf-type=UDM&group-id-list=udm-group-1");
    }

    @Test
    void cacheKeyIsStableForEquivalentQueries() {
        NfDiscoveryQuery first = NfDiscoveryQuery.builder()
                .targetNfType("UDM").serviceName("nudm-uecm").routingIndicator("1234").build();
        NfDiscoveryQuery second = NfDiscoveryQuery.builder()
                .targetNfType("UDM").serviceName("nudm-uecm").routingIndicator("1234").build();

        assertThat(first.cacheKey()).isEqualTo(second.cacheKey());
    }

    @Test
    void cacheKeyDiffersWhenTheSubscriberSelectorDiffers() {
        NfDiscoveryQuery first = NfDiscoveryQuery.builder()
                .targetNfType("UDM").routingIndicator("1234").build();
        NfDiscoveryQuery second = NfDiscoveryQuery.builder()
                .targetNfType("UDM").routingIndicator("9999").build();

        assertThat(first.cacheKey()).isNotEqualTo(second.cacheKey());
    }

    @Test
    void primaryServiceNameIsTheFirstNonBlankOne() {
        assertThat(NfDiscoveryQuery.builder().targetNfType("UDM").build().getPrimaryServiceName()).isNull();
        assertThat(NfDiscoveryQuery.builder().targetNfType("UDM").serviceName(" ").build()
                .getPrimaryServiceName()).isNull();
        assertThat(NfDiscoveryQuery.builder().targetNfType("UDM").serviceName(" nudm-uecm ").build()
                .getPrimaryServiceName()).isEqualTo("nudm-uecm");
    }
}
