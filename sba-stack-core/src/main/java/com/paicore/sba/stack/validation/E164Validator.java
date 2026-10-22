package com.paicore.sba.stack.validation;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * ITU-T E.164 number as 3GPP models it in SBI payloads: digits only, up to 15, with no
 * {@code +} prefix and no separators (see the {@code E164Number} datatype in TS 29.503).
 *
 * <p>{@link #normalize(String)} exists because operators routinely configure global titles in
 * dialling format ({@code +34 600 000 000}); sending that verbatim gets the whole payload
 * rejected by the peer, when the intended value is unambiguous.</p>
 */
public final class E164Validator {

    private static final Pattern E164_DIGITS = Pattern.compile("^[0-9]{1,15}$");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-().]");

    private E164Validator() {
        throw new IllegalStateException("Utility class");
    }

    /** @return {@code true} when {@code value} is 1-15 digits and nothing else */
    public static boolean isValid(String value) {
        return Objects.nonNull(value) && E164_DIGITS.matcher(value).matches();
    }

    /**
     * Strips a leading {@code +} and the usual separators (spaces, dashes, dots, parentheses).
     * Does not validate: the result may still not be a valid E.164 number.
     *
     * @return the normalized value, or {@code null} when {@code value} is {@code null}/blank
     */
    public static String normalize(String value) {
        if (Objects.isNull(value) || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("+")) {
            trimmed = trimmed.substring(1);
        }
        String stripped = SEPARATORS.matcher(trimmed).replaceAll("");
        return stripped.isEmpty() ? null : stripped;
    }
}
