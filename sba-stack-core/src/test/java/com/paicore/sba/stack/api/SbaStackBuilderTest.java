package com.paicore.sba.stack.api;

import com.paicore.sba.stack.api.config.DiscoveryConfig;
import com.paicore.sba.stack.api.config.Http2Config;
import com.paicore.sba.stack.api.config.SecurityConfig;
import com.paicore.sba.stack.api.n20.N20Client;
import com.paicore.sba.stack.api.n20.SmsSubscriptionData;
import com.paicore.sba.stack.api.nnrf.NFType;
import com.paicore.sba.stack.api.nnrf.NrfClient;
import com.paicore.sba.stack.exceptions.InvalidSupiException;
import com.paicore.sba.stack.utils.SdkConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SbaStackBuilderTest {

    private final Http2Config http2 = new Http2Config() {};
    private final SecurityConfig security = new SecurityConfig() {};
    private final DiscoveryConfig discovery = new DiscoveryConfig() {};

    @Test
    @DisplayName("§15: SbaStack.builder().http2().security().discovery().build() returns a wired stack")
    void buildsStackFromConfigurations() {
        try (SbaStack stack = SbaStack.builder()
                .http2(http2)
                .security(security)
                .discovery(discovery)
                .build()) {
            assertThat(stack).isNotNull();
            assertThat(stack.version()).isEqualTo(SdkConstants.SDK_VERSION);
            assertThat(stack.getN20Client()).isNotNull();
            assertThat(stack.getNrfClient()).isNotNull();
        }
    }

    @Test
    @DisplayName("§15: stack.getN20Client().getSmsSubscriptionData(supi) returns typed data")
    void n20ClientReturnsTypedSubscriptionData() {
        try (SbaStack stack = SbaStack.builder().build()) {
            N20Client n20 = stack.getN20Client();
            SmsSubscriptionData data = n20.getSmsSubscriptionData("imsi-460000000000001");
            assertThat(data.getSupi()).isEqualTo("imsi-460000000000001");
            assertThat(data.isSmsSubscribed()).isTrue();
        }
    }

    @Test
    @DisplayName("N20Client propagates SUPI validation errors")
    void n20RejectsInvalidSupi() {
        try (SbaStack stack = SbaStack.builder().build()) {
            N20Client n20 = stack.getN20Client();
            assertThrows(InvalidSupiException.class,
                    () -> n20.getSmsSubscriptionData("not-a-supi"));
        }
    }

    @Test
    @DisplayName("NrfClient stays callable even when no impl is registered on classpath")
    void nrfClientFallsBackToEmptyList() {
        try (SbaStack stack = SbaStack.builder().build()) {
            NrfClient nrf = stack.getNrfClient();
            assertThat(nrf.discoverInstances(NFType.UDM)).isEmpty();
        }
    }
}
