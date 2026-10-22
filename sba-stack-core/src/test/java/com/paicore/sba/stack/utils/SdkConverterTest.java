package com.paicore.sba.stack.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.paicore.sba.stack.openapi.model.AccessType;
import com.paicore.sba.stack.openapi.model.RatType;
import com.paicore.sba.stack.openapi.model.UeSmsContextData;
import com.paicore.sba.stack.exceptions.SdkException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdkConverterTest {

    @Test
    @DisplayName("valueAsString emits canonical enum values for the UE context")
    void serializesUeContextWithCanonicalEnumValues() {
        UeSmsContextData ctx = new UeSmsContextData()
                .supi("imsi-460000000000001")
                .accessType(AccessType._3_GPP_ACCESS)
                .ratType(RatType.NR);

        String json = SdkConverter.valueAsString(ctx);
        assertTrue(json.contains("\"supi\":\"imsi-460000000000001\""));
        assertTrue(json.contains("\"accessType\":\"3GPP_ACCESS\""));
        assertTrue(json.contains("\"ratType\":\"NR\""));
    }

    @Test
    @DisplayName("valueAsString → stringToObject round-trip preserves the UE context")
    void roundTripsUeContext() {
        UeSmsContextData original = new UeSmsContextData()
                .supi("imsi-460000000000001")
                .gpsi("msisdn-8613915900000")
                .accessType(AccessType._3_GPP_ACCESS);

        String json = SdkConverter.valueAsString(original);
        UeSmsContextData parsed = SdkConverter.stringToObject(json, UeSmsContextData.class);

        assertEquals(original.getSupi(), parsed.getSupi());
        assertEquals(original.getGpsi(), parsed.getGpsi());
        assertEquals(original.getAccessType(), parsed.getAccessType());
    }

    @Test
    @DisplayName("stringToObject surfaces deserialization failures as SdkException")
    void deserializationFailureSurfacesAsSdkException() {
        SdkException ex = assertThrows(SdkException.class,
                () -> SdkConverter.stringToObject("{not json", UeSmsContextData.class));
        assertEquals(ErrorCodes.INTERNAL_SERIALIZATION, ex.getCode());
    }

    @Test
    @DisplayName("stringToObject(TypeReference) deserializes parameterized containers")
    void stringToObjectWithTypeReferenceDeserializesContainers() {
        String json = "[\"a\",\"b\",\"c\"]";
        List<String> list = SdkConverter.stringToObject(json, new TypeReference<>() {});
        assertEquals(List.of("a", "b", "c"), list);
    }

    @Test
    @DisplayName("stringToObject(TypeReference) surfaces failures as SdkException")
    void stringToObjectTypeReferenceFailureSurfacesAsSdkException() {
        TypeReference<Map<String, String>> ref = new TypeReference<>() {};
        SdkException ex = assertThrows(SdkException.class,
                () -> SdkConverter.stringToObject("not json", ref));
        assertEquals(ErrorCodes.INTERNAL_SERIALIZATION, ex.getCode());
    }

    @Test
    @DisplayName("deepCopy produces an independent equal instance")
    void deepCopyProducesIndependentInstance() {
        UeSmsContextData original = new UeSmsContextData()
                .supi("imsi-1").gpsi("msisdn-2");

        UeSmsContextData copy = SdkConverter.deepCopy(original, UeSmsContextData.class);

        assertNotSame(original, copy);
        assertEquals(original.getSupi(), copy.getSupi());
        assertEquals(original.getGpsi(), copy.getGpsi());
    }

    @Test
    @DisplayName("valueAsString wraps Jackson failures as SdkException with INTERNAL_SERIALIZATION")
    void valueAsStringFailureSurfacesAsSdkException() {
        // Self-referential object trips Jackson into a serialization error.
        Object[] cycle = new Object[1];
        cycle[0] = cycle;
        SdkException ex = assertThrows(SdkException.class, () -> SdkConverter.valueAsString(cycle));
        assertEquals(ErrorCodes.INTERNAL_SERIALIZATION, ex.getCode());
    }
}
