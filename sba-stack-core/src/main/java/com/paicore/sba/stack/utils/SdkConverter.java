package com.paicore.sba.stack.utils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.paicore.sba.stack.exceptions.SdkException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class SdkConverter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .registerModule(new JavaTimeModule());

    private SdkConverter() {
        throw new IllegalStateException("Utility class");
    }

    public static String valueAsString(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize object to JSON: {}", e.getMessage());
            throw new SdkException(ErrorCodes.INTERNAL_SERIALIZATION, "Failed to serialize object to JSON", e);
        }
    }

    public static <T> T stringToObject(String json, Class<T> clazz) {
        try {
            return MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize JSON to {}: {}", clazz.getSimpleName(), e.getMessage());
            throw new SdkException(ErrorCodes.INTERNAL_SERIALIZATION, "Failed to deserialize JSON", e);
        }
    }

    public static <T> T stringToObject(String json, TypeReference<T> typeReference) {
        try {
            return MAPPER.readValue(json, typeReference);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize JSON: {}", e.getMessage());
            throw new SdkException(ErrorCodes.INTERNAL_SERIALIZATION, "Failed to deserialize JSON", e);
        }
    }

    public static <T> T deepCopy(T original, Class<T> clazz) {
        return MAPPER.convertValue(original, clazz);
    }

    /** Converts a loosely-typed value (e.g. a JSON-derived {@code Map}) into the target model type. */
    public static <T> T convertValue(Object value, Class<T> targetType) {
        return MAPPER.convertValue(value, targetType);
    }
}
