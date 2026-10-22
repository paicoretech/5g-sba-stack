package com.paicore.sba.stack.validation;

import com.paicore.sba.stack.exceptions.InvalidSupiException;
import com.paicore.sba.stack.utils.SdkConstants;

import java.util.regex.Pattern;

/**
 * 3GPP TS23.003 §2.2A: SUPI encoded as "imsi-<MCC><MNC><MSIN>" where the digit
 * portion is 6 to 15 decimal digits. NAI-based SUPIs are out of scope here.
 */
public final class SupiValidator {

    private static final Pattern IMSI_DIGITS = Pattern.compile("^\\d{6,15}$");

    private SupiValidator() {
        throw new IllegalStateException("Utility class");
    }

    public static boolean isValid(String supi) {
        if (supi == null || supi.isBlank()) {
            return false;
        }
        if (!supi.startsWith(SdkConstants.SUPI_PREFIX_IMSI)) {
            return false;
        }
        String digits = supi.substring(SdkConstants.SUPI_PREFIX_IMSI.length());
        return IMSI_DIGITS.matcher(digits).matches();
    }

    public static void requireValid(String supi) {
        if (!isValid(supi)) {
            throw new InvalidSupiException("Expected '" + SdkConstants.SUPI_PREFIX_IMSI
                    + "<6-15 digits>' but got: " + supi);
        }
    }
}
