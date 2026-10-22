package com.paicore.sba.stack.api.n21;

import com.paicore.sba.stack.openapi.model.Guami;
import com.paicore.sba.stack.openapi.model.NetworkNodeDiameterAddress;
import com.paicore.sba.stack.openapi.model.PlmnId;
import com.paicore.sba.stack.openapi.model.SmsfRegistration;
import com.paicore.sba.stack.openapi.model.UeSmsContextData;
import com.paicore.sba.stack.utils.SdkConverter;
import com.paicore.sba.stack.validation.E164Validator;
import com.paicore.sba.stack.validation.FqdnValidator;
import lombok.extern.slf4j.Slf4j;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure mapper that builds the N21 {@link SmsfRegistration} payload (3GPP TS 29.503)
 * from the local {@link SmsfContext} and the AMF-provided {@link UeSmsContextData}.
 *
 * <p>This class performs no I/O. The SUPI carried by {@code UeSmsContextData} is
 * used by the client to build the request URI, not the payload. The serving
 * {@code plmnId} is taken from the UE's GUAMI (i.e. the PLMN received from the
 * AMF is forwarded to the UDM), falling back to the SMSF's home PLMN when the AMF
 * supplies no GUAMI — {@code guamis} is optional in TS 29.540, but {@code plmnId}
 * is mandatory in the registration.</p>
 */
@Slf4j
public final class N21RegistrationMapper {

    private N21RegistrationMapper() {
    }

    /**
     * Builds an {@link SmsfRegistration} stamped with the current time.
     *
     * @param smsfContext local SMSF identity (instance id, PLMN, optional MAP/Diameter addresses)
     * @param ueContext   UE SMS context received from the AMF (source of the SUPI)
     * @return the populated registration payload
     * @throws IllegalArgumentException when required identity fields are missing or malformed
     */
    public static SmsfRegistration buildRegistration(SmsfContext smsfContext, UeSmsContextData ueContext) {
        return buildRegistration(smsfContext, ueContext, OffsetDateTime.now());
    }

    /**
     * Builds an {@link SmsfRegistration} with an explicit registration time, keeping the
     * mapper deterministic for callers that supply their own clock.
     *
     * @param registrationTime value for {@code registrationTime}; {@code null} omits the field
     */
    public static SmsfRegistration buildRegistration(SmsfContext smsfContext,
                                                     UeSmsContextData ueContext,
                                                     OffsetDateTime registrationTime) {
        Objects.requireNonNull(smsfContext, "smsfContext must not be null");
        Objects.requireNonNull(ueContext, "ueContext must not be null");

        SmsfRegistration registration = new SmsfRegistration()
                .smsfInstanceId(parseInstanceId(smsfContext.getSmsfInstanceId()))
                .plmnId(resolvePlmn(smsfContext, ueContext));
        clearGeneratedDefaults(registration);

        if (Objects.nonNull(registrationTime)) {
            registration.setRegistrationTime(registrationTime);
        }
        if (isPresent(smsfContext.getSmsfSetId())) {
            registration.setSmsfSetId(smsfContext.getSmsfSetId().trim());
        }
        if (isPresent(smsfContext.getSupportedFeatures())) {
            registration.setSupportedFeatures(smsfContext.getSupportedFeatures().trim());
        }
        registration.setSmsfSbiSupInd(smsfContext.getSmsfSbiSupInd());
        registration.setSmsfMAPAddress(resolveMapAddress(smsfContext.getSmsfMapAddress()));
        registration.setSmsfDiameterAddress(resolveDiameterAddress(smsfContext.getSmsfDiameterAddress()));
        return registration;
    }

    /**
     * Clears the non-null defaults the OpenAPI generator gives the model.
     *
     * <p>{@code resetIds} defaults to an empty list and the two indicators to {@code false}, and
     * since the serializer only drops nulls they would all reach the UDM. An empty
     * {@code resetIds} violates the schema's {@code minItems: 1}, and a {@code false} indicator
     * is a claim about this SMSF that the caller never made — both must be absent unless set.</p>
     */
    private static void clearGeneratedDefaults(SmsfRegistration registration) {
        registration.setResetIds(null);
        registration.setUdrRestartInd(null);
        registration.setSmsfSbiSupInd(null);
    }

    /**
     * Normalizes the configured global title into the digits-only form the schema requires.
     *
     * <p>An unusable value is dropped rather than propagated: {@code smsfMAPAddress} is an
     * optional interworking hint, so sending it malformed would get the whole registration
     * rejected with a 400 and leave the subscriber unregistered — losing far more than the
     * MAP hint itself.</p>
     */
    private static String resolveMapAddress(String configured) {
        if (!isPresent(configured)) {
            return null;
        }
        String normalized = E164Validator.normalize(configured);
        if (!E164Validator.isValid(normalized)) {
            log.warn("Ignoring smsfMAPAddress '{}': not an E.164 number (1-15 digits) after normalization",
                    configured);
            return null;
        }
        return normalized;
    }

    /**
     * Converts a flat Diameter address into the spec's {@code name}/{@code realm} object.
     * Accepts {@code name@realm}; a bare identity is used as both name and realm. Dropped when
     * either part is not a valid FQDN, for the same reason as the MAP address.
     */
    private static NetworkNodeDiameterAddress resolveDiameterAddress(String configured) {
        if (!isPresent(configured)) {
            return null;
        }
        NetworkNodeDiameterAddress address = buildDiameterAddress(configured.trim());
        if (!FqdnValidator.isValid(address.getName()) || !FqdnValidator.isValid(address.getRealm())) {
            log.warn("Ignoring smsfDiameterAddress '{}': name/realm are not valid FQDNs (TS 29.571)", configured);
            return null;
        }
        return address;
    }

    private static UUID parseInstanceId(String smsfInstanceId) {
        if (!isPresent(smsfInstanceId)) {
            throw new IllegalArgumentException("smsfContext.smsfInstanceId is required");
        }
        try {
            return UUID.fromString(smsfInstanceId.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "smsfContext.smsfInstanceId is not a valid UUID: " + smsfInstanceId, e);
        }
    }

    /**
     * Serving PLMN first (the one the AMF reported, forwarded verbatim), then the SMSF's
     * configured home PLMN.
     */
    private static PlmnId resolvePlmn(SmsfContext smsfContext, UeSmsContextData ueContext) {
        PlmnId fromGuami = extractServingPlmn(ueContext);
        if (Objects.nonNull(fromGuami)) {
            return fromGuami;
        }
        PlmnId homePlmn = buildHomePlmn(smsfContext);
        if (Objects.nonNull(homePlmn)) {
            return homePlmn;
        }
        throw new IllegalArgumentException(
                "Cannot derive plmnId: UeSmsContextData has no guami with a plmnId and "
                        + "smsfContext has no home PLMN configured");
    }

    /** @return the PLMN of the UE's first GUAMI, or {@code null} when the AMF supplied none. */
    private static PlmnId extractServingPlmn(UeSmsContextData ueContext) {
        List<Guami> guamis = ueContext.getGuamis();
        if (Objects.isNull(guamis) || guamis.isEmpty() || Objects.isNull(guamis.get(0))
                || Objects.isNull(guamis.get(0).getPlmnId())) {
            return null;
        }
        PlmnId plmnId = SdkConverter.convertValue(guamis.get(0).getPlmnId(), PlmnId.class);
        if (Objects.isNull(plmnId) || !isPresent(plmnId.getMcc()) || !isPresent(plmnId.getMnc())) {
            throw new IllegalArgumentException("Guami.plmnId is missing mcc/mnc");
        }
        return plmnId;
    }

    private static PlmnId buildHomePlmn(SmsfContext smsfContext) {
        if (!isPresent(smsfContext.getHomePlmnMcc()) || !isPresent(smsfContext.getHomePlmnMnc())) {
            return null;
        }
        return new PlmnId()
                .mcc(smsfContext.getHomePlmnMcc().trim())
                .mnc(smsfContext.getHomePlmnMnc().trim());
    }

    private static NetworkNodeDiameterAddress buildDiameterAddress(String diameterAddress) {
        int at = diameterAddress.indexOf('@');
        if (at > 0 && at < diameterAddress.length() - 1) {
            return new NetworkNodeDiameterAddress()
                    .name(diameterAddress.substring(0, at))
                    .realm(diameterAddress.substring(at + 1));
        }
        return new NetworkNodeDiameterAddress()
                .name(diameterAddress)
                .realm(diameterAddress);
    }

    private static boolean isPresent(String value) {
        return Objects.nonNull(value) && !value.isBlank();
    }
}
