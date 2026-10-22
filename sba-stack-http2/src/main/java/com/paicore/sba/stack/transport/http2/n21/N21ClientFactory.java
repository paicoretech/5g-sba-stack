package com.paicore.sba.stack.transport.http2.n21;

import com.paicore.sba.stack.api.n21.N21Client;
import com.paicore.sba.stack.api.n21.N21Configuration;
import com.paicore.sba.stack.api.n21.SmsfContext;
import com.paicore.sba.stack.api.security.AuthorizationProvider;
import com.paicore.sba.stack.transport.http2.Http2Client;

import java.util.function.Supplier;

/**
 * Entry point for building {@link N21Client} instances from a consuming module
 * (e.g. the SMSF Module) without exposing HTTP/2 wiring.
 *
 * <p>The consumer maps its own gateway configuration into {@link N21Configuration}
 * and {@link SmsfContext}, then obtains a client here. The returned client is not
 * connected yet: call {@link N21Client#connect()} on demand.</p>
 */
public final class N21ClientFactory {

    private N21ClientFactory() {
    }

    /**
     * Creates an N21 client that resolves the UDM apiRoot from
     * {@link N21Configuration#getApiRoot()} (NRF discovery must be disabled).
     */
    public static N21Client create(N21Configuration config, SmsfContext smsfContext) {
        return createWithProvider(config, smsfContext, null, null);
    }

    /**
     * Creates an N21 client, supplying the already-connected NRF client used for
     * UDM discovery when {@link N21Configuration#isNrfDiscoveryEnabled()} is {@code true}.
     * Pass the per-gateway NRF transport the consuming module already runs.
     *
     * @param nrfClient connected NRF HTTP/2 client (required only when discovery is enabled)
     */
    public static N21Client create(N21Configuration config, SmsfContext smsfContext, Http2Client nrfClient) {
        return createWithProvider(config, smsfContext, nrfClient, null);
    }

    /**
     * Creates a fully-configured N21 client.
     *
     * @param nrfClient                  connected NRF client for discovery (nullable when discovery is disabled)
     * @param authorizationTokenProvider supplies the OAuth2 authorization header value on each request
     *                                   (nullable; consulted only when OAuth2 is enabled). A {@link Supplier}
     *                                   so a rotating/refreshed token is always current.
     */
    public static N21Client create(N21Configuration config,
                                   SmsfContext smsfContext,
                                   Http2Client nrfClient,
                                   Supplier<String> authorizationTokenProvider) {
        return new N21ClientImpl(config, smsfContext, nrfClient,
                AuthorizationProvider.of(authorizationTokenProvider));
    }

    /**
     * Creates a fully-configured N21 client from an {@link AuthorizationProvider}.
     *
     * @param nrfClient             connected NRF client for discovery (nullable when discovery is disabled)
     * @param authorizationProvider supplies the authorization header value on each request
     */
    public static N21Client createWithProvider(N21Configuration config,
                                               SmsfContext smsfContext,
                                               Http2Client nrfClient,
                                               AuthorizationProvider authorizationProvider) {
        return new N21ClientImpl(config, smsfContext, nrfClient, authorizationProvider);
    }
}
