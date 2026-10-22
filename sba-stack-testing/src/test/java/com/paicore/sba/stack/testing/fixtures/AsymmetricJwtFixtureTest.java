package com.paicore.sba.stack.testing.fixtures;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.paicore.sba.stack.security.auth.AsymmetricJwtAuthenticator;
import com.paicore.sba.stack.security.auth.JwkSetLoader;
import com.paicore.sba.stack.security.auth.JwksKeyStore;
import com.paicore.sba.stack.security.auth.Principal;
import com.paicore.sba.stack.security.exceptions.AuthenticationException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsymmetricJwtFixtureTest {

    private HttpServer server;
    private URI jwksUri;
    private AsymmetricKeyFixture keys;

    @BeforeEach
    void start() throws IOException {
        keys = AsymmetricKeyFixture.rsa("kid-fix");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            byte[] body = keys.publicJwksJson().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        jwksUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/jwks");
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("RS256 token round-trips through AsymmetricJwtAuthenticator")
    void rs256RoundTrip() {
        String token = AsymmetricJwtFixture.rs256(keys)
                .issuer("https://issuer.local")
                .subject("amf-001")
                .audience("nsmsf-sms")
                .scope("nsmsf-sms")
                .validity(Duration.ofMinutes(5))
                .sign();

        Principal principal = authenticator().authenticate(token);

        assertThat(principal.getId()).isEqualTo("amf-001");
        assertThat(principal.getIssuer()).isEqualTo("https://issuer.local");
        assertThat(principal.getScopes()).containsExactly("nsmsf-sms");
    }

    @Test
    @DisplayName("ES256 token round-trips when JWKS publishes the matching EC key")
    void es256RoundTrip() {
        AsymmetricKeyFixture ec = AsymmetricKeyFixture.ec("kid-ec");
        server.removeContext("/jwks");
        server.createContext("/jwks", exchange -> {
            byte[] body = ec.publicJwksJson().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        String token = AsymmetricJwtFixture.es256(ec)
                .issuer("https://issuer.local")
                .subject("amf-002")
                .audience("nsmsf-sms")
                .scope("nsmsf-sms")
                .sign();

        Principal principal = authenticator().authenticate(token);
        assertThat(principal.getId()).isEqualTo("amf-002");
    }

    @Test
    @DisplayName("expiredBy issues a token already past its exp")
    void expiredTokenRejected() {
        String token = AsymmetricJwtFixture.rs256(keys)
                .issuer("https://issuer.local")
                .audience("nsmsf-sms")
                .scope("nsmsf-sms")
                .expiredBy(Duration.ofMinutes(10))
                .sign();
        AsymmetricJwtAuthenticator authenticator = authenticator();

        assertThatThrownBy(() -> authenticator.authenticate(token))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    @DisplayName("extra claims survive to the signed JWT")
    void extraClaimsPreserved() throws Exception {
        String token = AsymmetricJwtFixture.rs256(keys)
                .claim("nf_instance_id", "nf-001")
                .sign();

        SignedJWT signed = SignedJWT.parse(token);
        JWTClaimsSet claims = signed.getJWTClaimsSet();
        assertThat(claims.getClaim("nf_instance_id")).isEqualTo("nf-001");
    }

    @Test
    @DisplayName("multiple scopes are joined into a single space-separated claim")
    void multipleScopesJoined() throws Exception {
        String token = AsymmetricJwtFixture.rs256(keys)
                .scopes("nsmsf-sms", "nudm-sdm")
                .sign();

        SignedJWT signed = SignedJWT.parse(token);
        assertThat((String) signed.getJWTClaimsSet().getClaim("scope"))
                .isEqualTo("nsmsf-sms nudm-sdm");
    }

    @Test
    @DisplayName("AsymmetricKeyFixture publicJwks exposes the public key only")
    void publicJwksOmitsPrivate() {
        AsymmetricKeyFixture k = AsymmetricKeyFixture.rsa("kid-pub");
        JWKSet jwks = k.publicJwks();
        assertThat(jwks.getKeys()).hasSize(1);
        assertThat(jwks.getKeys().get(0).isPrivate()).isFalse();
        assertThat(jwks.getKeys().get(0).getKeyID()).isEqualTo("kid-pub");
    }

    @Test
    @DisplayName("wrap accepts an externally-built JWK")
    void wrapAcceptsExistingJwk() {
        AsymmetricKeyFixture original = AsymmetricKeyFixture.rsa("kid-wrap");
        AsymmetricKeyFixture wrapped = AsymmetricKeyFixture.wrap(original.jwk());
        assertThat(wrapped.kid()).isEqualTo("kid-wrap");
        assertThat(wrapped.asRsa().getKeyID()).isEqualTo("kid-wrap");
    }

    @Test
    @DisplayName("asEc on an RSA key throws clearly")
    void asEcRejectsRsa() {
        AsymmetricKeyFixture k = AsymmetricKeyFixture.rsa("kid-x");
        assertThatThrownBy(k::asEc).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("asRsa on an EC key throws clearly")
    void asRsaRejectsEc() {
        AsymmetricKeyFixture k = AsymmetricKeyFixture.ec("kid-y");
        assertThatThrownBy(k::asRsa).isInstanceOf(IllegalStateException.class);
    }
    private AsymmetricJwtAuthenticator authenticator() {
        JwksKeyStore store = new JwksKeyStore(httpLoader());
        return AsymmetricJwtAuthenticator.builder()
                .jwksKeyStore(store)
                .jwksUri(jwksUri)
                .expectedIssuer("https://issuer.local")
                .expectedAudience("nsmsf-sms")
                .requiredScopes(Set.of("nsmsf-sms"))
                .build();
    }

    private JwkSetLoader httpLoader() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        return () -> {
            try {
                HttpResponse<String> res = client.send(
                        HttpRequest.newBuilder(jwksUri).timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                return JWKSet.parse(res.body());
            } catch (IOException | ParseException ex) {
                throw new AuthenticationException("Loader failure: " + ex.getMessage(), ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AuthenticationException("Loader interrupted: " + ex.getMessage(), ex);
            }
        };
    }
}
