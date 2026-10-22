package com.paicore.sba.stack.api.nnrf.disc;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class NfInstanceSelectorTest {

    private static final Instant NOW = Instant.parse("2026-07-29T10:00:00Z");
    private static final String SERVICE = "nudm-uecm";

    @Test
    void prefersTheApiPrefixOfTheRequestedService() {
        String body = """
                {"nfInstances":[{"nfInstanceId":"udm-1","nfStatus":"REGISTERED","nfServices":[
                  {"serviceName":"nudm-sdm","apiPrefix":"https://sdm.example.com:8443"},
                  {"serviceName":"nudm-uecm","apiPrefix":"https://uecm.example.com:8443"}]}]}
                """;

        NfDiscoveryResult result = NfInstanceSelector.parse(body, SERVICE, NOW, null);

        assertThat(result.preferredApiRoot()).contains("https://uecm.example.com:8443");
    }

    @Test
    void composesTheApiRootFromSchemeFqdnAndIpEndPointPort() {
        String body = """
                {"nfInstances":[{"nfInstanceId":"udm-1","fqdn":"udm.example.com","nfServices":[
                  {"serviceName":"nudm-uecm","scheme":"https","ipEndPoints":[{"port":9443}]}]}]}
                """;

        NfDiscoveryResult result = NfInstanceSelector.parse(body, SERVICE, NOW, null);

        assertThat(result.preferredApiRoot()).contains("https://udm.example.com:9443");
    }

    @Test
    void fallsBackToTheInstanceIpv4AddressWhenNoFqdnIsPresent() {
        String body = """
                {"nfInstances":[{"nfInstanceId":"udm-1","ipv4Addresses":["10.0.0.7"],"nfServices":[
                  {"serviceName":"nudm-uecm","scheme":"http"}]}]}
                """;

        NfDiscoveryResult result = NfInstanceSelector.parse(body, SERVICE, NOW, null);

        assertThat(result.preferredApiRoot()).contains("http://10.0.0.7");
    }

    @Test
    void skipsInstancesThatAreNotRegistered() {
        String body = """
                {"nfInstances":[
                  {"nfInstanceId":"udm-suspended","nfStatus":"SUSPENDED","nfServices":[
                    {"serviceName":"nudm-uecm","apiPrefix":"https://suspended:8443"}]},
                  {"nfInstanceId":"udm-ok","nfStatus":"REGISTERED","nfServices":[
                    {"serviceName":"nudm-uecm","apiPrefix":"https://ok:8443"}]}]}
                """;

        NfDiscoveryResult result = NfInstanceSelector.parse(body, SERVICE, NOW, null);

        assertThat(result.instances()).hasSize(1);
        assertThat(result.preferredApiRoot()).contains("https://ok:8443");
    }

    @Test
    void skipsInstancesThatDoNotExposeTheRequestedService() {
        String body = """
                {"nfInstances":[{"nfInstanceId":"udm-1","nfServices":[
                  {"serviceName":"nudm-sdm","apiPrefix":"https://sdm:8443"}]}]}
                """;

        NfDiscoveryResult result = NfInstanceSelector.parse(body, SERVICE, NOW, null);

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void acceptsAReducedProfileWhoseServicesCarryNoName() {
        String body = """
                {"nfInstances":[{"nfInstanceId":"udm-1","nfServices":[
                  {"apiPrefix":"https://udm.example.com:8443"}]}]}
                """;

        NfDiscoveryResult result = NfInstanceSelector.parse(body, SERVICE, NOW, null);

        assertThat(result.preferredApiRoot()).contains("https://udm.example.com:8443");
    }

    @Test
    void exposesTheServiceNameAndApiVersionForUriComposition() {
        String body = """
                {"nfInstances":[{"nfInstanceId":"udm-1","nfServices":[
                  {"serviceName":"nudm-uecm","versions":[{"apiVersionInUri":"v2","apiFullVersion":"2.0.0"}],
                   "apiPrefix":"https://udm.example.com:8443"}]}]}
                """;

        DiscoveredNfInstance instance = NfInstanceSelector.parse(body, SERVICE, NOW, null)
                .preferred().orElseThrow();

        assertThat(instance.serviceName()).isEqualTo("nudm-uecm");
        assertThat(instance.apiVersion()).isEqualTo("v2");
    }

    @Test
    void apiVersionIsNullWhenTheProfileAdvertisesNone() {
        String body = """
                {"nfInstances":[{"nfInstanceId":"udm-1","nfServices":[
                  {"serviceName":"nudm-uecm","apiPrefix":"https://udm.example.com:8443"}]}]}
                """;

        DiscoveredNfInstance instance = NfInstanceSelector.parse(body, SERVICE, NOW, null)
                .preferred().orElseThrow();

        assertThat(instance.apiVersion()).isNull();
        assertThat(instance.serviceName()).isEqualTo("nudm-uecm");
    }

    @Test
    void ranksByPriorityThenCapacityThenLoad() {
        String body = """
                {"nfInstances":[
                  {"nfInstanceId":"c","priority":10,"capacity":100,"load":10,"fqdn":"c.example.com"},
                  {"nfInstanceId":"a","priority":1,"capacity":50,"load":80,"fqdn":"a.example.com"},
                  {"nfInstanceId":"b","priority":1,"capacity":100,"load":80,"fqdn":"b.example.com"}]}
                """;

        NfDiscoveryResult result = NfInstanceSelector.parse(body, null, NOW, null);

        assertThat(result.instances()).extracting(DiscoveredNfInstance::nfInstanceId)
                .containsExactly("b", "a", "c");
    }

    @Test
    void serviceLevelPriorityOverridesTheInstanceLevelOne() {
        String body = """
                {"nfInstances":[
                  {"nfInstanceId":"a","priority":1,"fqdn":"a.example.com","nfServices":[
                    {"serviceName":"nudm-uecm","priority":99,"apiPrefix":"https://a:8443"}]},
                  {"nfInstanceId":"b","priority":50,"fqdn":"b.example.com","nfServices":[
                    {"serviceName":"nudm-uecm","priority":2,"apiPrefix":"https://b:8443"}]}]}
                """;

        NfDiscoveryResult result = NfInstanceSelector.parse(body, SERVICE, NOW, null);

        assertThat(result.preferred().map(DiscoveredNfInstance::nfInstanceId)).contains("b");
    }

    @Test
    void unrankedInstancesSortAfterRankedOnes() {
        String body = """
                {"nfInstances":[
                  {"nfInstanceId":"unranked","fqdn":"u.example.com"},
                  {"nfInstanceId":"ranked","priority":5,"fqdn":"r.example.com"}]}
                """;

        NfDiscoveryResult result = NfInstanceSelector.parse(body, null, NOW, null);

        assertThat(result.instances()).extracting(DiscoveredNfInstance::nfInstanceId)
                .containsExactly("ranked", "unranked");
    }

    @Test
    void validityPeriodBecomesTheExpiry() {
        String body = """
                {"validityPeriod":600,"nfInstances":[{"nfInstanceId":"a","fqdn":"a.example.com"}]}
                """;

        NfDiscoveryResult result = NfInstanceSelector.parse(body, null, NOW, Duration.ofSeconds(30));

        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(result.isExpired(NOW.plusSeconds(599))).isFalse();
        assertThat(result.isExpired(NOW.plusSeconds(600))).isTrue();
    }

    @Test
    void defaultTtlAppliesWhenTheNrfOmitsTheValidityPeriod() {
        String body = """
                {"nfInstances":[{"nfInstanceId":"a","fqdn":"a.example.com"}]}
                """;

        NfDiscoveryResult result = NfInstanceSelector.parse(body, null, NOW, Duration.ofSeconds(30));

        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void withoutValidityPeriodOrDefaultTtlTheResultNeverExpires() {
        String body = """
                {"nfInstances":[{"nfInstanceId":"a","fqdn":"a.example.com"}]}
                """;

        NfDiscoveryResult result = NfInstanceSelector.parse(body, null, NOW, null);

        assertThat(result.expiresAt()).isNull();
        assertThat(result.isExpired(NOW.plusSeconds(86_400))).isFalse();
    }

    @Test
    void malformedOrEmptyBodiesYieldAnEmptyResult() {
        assertThat(NfInstanceSelector.parse(null, SERVICE, NOW, null).isEmpty()).isTrue();
        assertThat(NfInstanceSelector.parse("   ", SERVICE, NOW, null).isEmpty()).isTrue();
        assertThat(NfInstanceSelector.parse("not json", SERVICE, NOW, null).isEmpty()).isTrue();
        assertThat(NfInstanceSelector.parse("{\"nfInstances\":[]}", SERVICE, NOW, null).isEmpty()).isTrue();
    }

    @Test
    void instancesWithoutAResolvableEndpointAreSkipped() {
        String body = """
                {"nfInstances":[{"nfInstanceId":"no-endpoint","nfServices":[{"serviceName":"nudm-uecm"}]}]}
                """;

        assertThat(NfInstanceSelector.parse(body, SERVICE, NOW, null).isEmpty()).isTrue();
    }
}
