package com.paicore.sba.stack.validation;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Fully Qualified Domain Name as constrained by 3GPP TS 29.571 ({@code Fqdn}), which is also
 * what {@code DiameterIdentity} resolves to.
 *
 * <p>The pattern requires at least one dot-separated label followed by an alphabetic
 * top-level label, so a bare host name such as {@code smsfnode} is not a valid Diameter
 * identity even though it looks reasonable in configuration.</p>
 */
public final class FqdnValidator {

    private static final int MIN_LENGTH = 4;
    private static final int MAX_LENGTH = 253;

    private static final Pattern FQDN = Pattern.compile(
            "^([0-9A-Za-z]([-0-9A-Za-z]{0,61}[0-9A-Za-z])?\\.)+[A-Za-z]{2,63}\\.?$");

    private FqdnValidator() {
        throw new IllegalStateException("Utility class");
    }

    /** @return {@code true} when {@code value} satisfies the TS 29.571 {@code Fqdn} constraints */
    public static boolean isValid(String value) {
        if (Objects.isNull(value)) {
            return false;
        }
        int length = value.length();
        return length >= MIN_LENGTH && length <= MAX_LENGTH && FQDN.matcher(value).matches();
    }
}
