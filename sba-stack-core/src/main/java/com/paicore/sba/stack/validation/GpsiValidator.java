package com.paicore.sba.stack.validation;

import com.paicore.sba.stack.exceptions.InvalidGpsiException;
import com.paicore.sba.stack.utils.SdkConstants;

import java.util.regex.Pattern;

/**
 * 3GPP TS23.003 §28.16: GPSI encoded as "msisdn-<digits>" (E.164 number,
 * up to 15 digits). External-ID GPSIs are out of scope here.
 */
public final class GpsiValidator {

    private static final Pattern MSISDN_DIGITS = Pattern.compile("^\\d{1,15}$");

    private GpsiValidator() {
        throw new IllegalStateException("Utility class");
    }

    public static boolean isValid(String gpsi) {
        if (gpsi == null || gpsi.isBlank()) {
            return false;
        }
        if (!gpsi.startsWith(SdkConstants.GPSI_PREFIX_MSISDN)) {
            return false;
        }
        String digits = gpsi.substring(SdkConstants.GPSI_PREFIX_MSISDN.length());
        return MSISDN_DIGITS.matcher(digits).matches();
    }

    public static void requireValid(String gpsi) {
        if (!isValid(gpsi)) {
            throw new InvalidGpsiException("Expected '" + SdkConstants.GPSI_PREFIX_MSISDN
                    + "<1-15 digits>' but got: " + gpsi);
        }
    }
}
