package com.paicore.sba.stack.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class FqdnValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"smsf.node", "smsf.example.com", "smsf-1.epc.mnc007.mcc214.3gppnetwork.org",
            "example.com."})
    void acceptsValidFqdns(String value) {
        assertThat(FqdnValidator.isValid(value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"smsfnode", "a.b", "", " ", ".example.com", "smsf..com", "-smsf.com",
            "smsf.c0m", "http://smsf.example.com"})
    void rejectsInvalidFqdns(String value) {
        assertThat(FqdnValidator.isValid(value)).isFalse();
    }

    @Test
    void rejectsNull() {
        assertThat(FqdnValidator.isValid(null)).isFalse();
    }

    @Test
    void rejectsValuesLongerThanTheSchemaAllows() {
        String label = "a".repeat(61);
        String tooLong = (label + ".").repeat(4) + "example.com";

        assertThat(tooLong.length()).isGreaterThan(253);
        assertThat(FqdnValidator.isValid(tooLong)).isFalse();
    }
}
