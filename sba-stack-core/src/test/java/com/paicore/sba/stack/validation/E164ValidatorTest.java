package com.paicore.sba.stack.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class E164ValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"1", "34600000000", "123456789012345"})
    void acceptsDigitsOnlyUpToFifteen(String value) {
        assertThat(E164Validator.isValid(value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "+34600000000", "34 600 000 000", "34-600-000", "abc",
            "1234567890123456"})
    void rejectsAnythingElse(String value) {
        assertThat(E164Validator.isValid(value)).isFalse();
    }

    @Test
    void rejectsNull() {
        assertThat(E164Validator.isValid(null)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "'+34600000000', 34600000000",
            "'+34 600-000.000', 34600000000",
            "'(34) 600 000 000', 34600000000",
            "'  34600000000  ', 34600000000"
    })
    void normalizesDiallingFormats(String raw, String expected) {
        assertThat(E164Validator.normalize(raw)).isEqualTo(expected);
    }

    @Test
    void normalizeReturnsNullWhenThereIsNothingLeft() {
        assertThat(E164Validator.normalize(null)).isNull();
        assertThat(E164Validator.normalize("   ")).isNull();
        assertThat(E164Validator.normalize("+")).isNull();
        assertThat(E164Validator.normalize("- ()")).isNull();
    }

    @Test
    void normalizeDoesNotValidate() {
        assertThat(E164Validator.normalize("+abc")).isEqualTo("abc");
        assertThat(E164Validator.isValid(E164Validator.normalize("+abc"))).isFalse();
    }
}
