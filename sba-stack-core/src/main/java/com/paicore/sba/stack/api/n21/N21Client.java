package com.paicore.sba.stack.api.n21;

import com.paicore.sba.stack.openapi.model.SmsfRegistration;
import com.paicore.sba.stack.openapi.model.UeSmsContextData;

/**
 * High-level N21 client encapsulating SMSF &rarr; UDM communication (3GPP TS 29.503,
 * Nudm_UECM SMSF registration for 3GPP access).
 *
 * <p>The client hides all HTTP/2, mTLS, OAuth2 and (optional) NRF-discovery
 * details from the consuming module. Connection management is explicit and
 * separate from sending, so a consumer can open the transport on demand:</p>
 *
 * <pre>{@code
 * N21Client client = N21ClientFactory.create(config, smsfContext, nrfClient);
 * client.connect();                                   // opens HTTP/2 (resolves UDM apiRoot)
 * SmsfRegistrationResponse res =
 *     client.registerSubscriber(path, ueSmsContextData); // builds payload + PUT
 * }</pre>
 *
 * <p>The stack owns only protocol concerns; it does not implement SMSF business
 * logic (persistence, registration lifecycle, retries).</p>
 */
public interface N21Client extends AutoCloseable {

    /**
     * Opens the HTTP/2 connection to the UDM on demand. When NRF discovery is
     * enabled, the UDM apiRoot is resolved first via the injected NRF client.
     * Idempotent: calling it on an already-connected client is a no-op.
     */
    void connect();

    /** @return {@code true} when the UDM transport is connected and active. */
    boolean isConnected();

    /**
     * Builds the N21 registration payload from the configured {@link SmsfContext}
     * and the supplied UE context. Pure operation, performs no I/O.
     *
     * @param ueContext UE SMS context received from the AMF (source of the SUPI)
     * @return the populated {@link SmsfRegistration}
     */
    SmsfRegistration buildRegistration(UeSmsContextData ueContext);

    /**
     * Registers the subscriber with the UDM: builds the payload from {@code ueContext}
     * and issues an HTTP/2 PUT to {@code path} (relative to the resolved apiRoot).
     *
     * @param path      registration resource path; {@code {supi}}/{@code {ueId}} placeholders
     *                  are substituted from {@code ueContext}
     * @param ueContext UE SMS context received from the AMF
     * @return a typed {@link SmsfRegistrationResponse}
     */
    SmsfRegistrationResponse registerSubscriber(String path, UeSmsContextData ueContext);

    /**
     * Deregisters the subscriber with the UDM via HTTP/2 DELETE to {@code path}.
     *
     * @param path      deregistration resource path (placeholders substituted from {@code ueContext})
     * @param ueContext UE SMS context received from the AMF (source of the SUPI)
     * @return a typed {@link SmsfRegistrationResponse}
     */
    SmsfRegistrationResponse deregisterSubscriber(String path, UeSmsContextData ueContext);

    /**
     * Reads the current SMSF registration for {@code supi} via HTTP/2 GET to {@code path}.
     *
     * @param path resource path ({@code {supi}}/{@code {ueId}} placeholders substituted from {@code supi})
     * @param supi subscriber permanent identifier
     * @return a typed {@link SmsfRegistrationResponse} with the parsed registration on success
     */
    SmsfRegistrationResponse getRegistration(String path, String supi);

    /** Closes the underlying UDM transport. Never throws. */
    @Override
    void close();
}
