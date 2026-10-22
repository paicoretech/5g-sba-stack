package com.paicore.sba.stack.validation;

import com.paicore.sba.stack.exceptions.InvalidPeiException;
import com.paicore.sba.stack.utils.SdkConstants;

import java.util.regex.Pattern;

/**
 * 3GPP TS23.003 §6.2: PEI encoded as "imei-<14 digits><check digit>" for IMEI,
 * or "imeisv-<16 digits>" for IMEISV. This validator covers the IMEI form.
 */
public final class PeiValidator {

    private static final Pattern IMEI_DIGITS = Pattern.compile("^\\d{15}$");

    private PeiValidator() {
        throw new IllegalStateException("Utility class");
    }

    public static boolean isValid(String pei) {
        if (pei == null || pei.isBlank()) {
            return false;
        }
        if (!pei.startsWith(SdkConstants.PEI_PREFIX_IMEI)) {
            return false;
        }
        String digits = pei.substring(SdkConstants.PEI_PREFIX_IMEI.length());
        if (!IMEI_DIGITS.matcher(digits).matches()) {
            return false;
        }
        return luhnCheck(digits);
    }

    public static void requireValid(String pei) {
        if (!isValid(pei)) {
            throw new InvalidPeiException("Expected '" + SdkConstants.PEI_PREFIX_IMEI
                    + "<15 digits, valid Luhn>' but got: " + pei);
        }
    }

    private static boolean luhnCheck(String digits) {
        int sum = 0;
        boolean alternate = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }
}
