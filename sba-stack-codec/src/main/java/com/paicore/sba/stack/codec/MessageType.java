package com.paicore.sba.stack.codec;

/**
 * 3GPP TS23.040 §9.2.3.1 SMS TPDU types as exposed by the SDK codec.
 */
public enum MessageType {

    SMS_DELIVER,
    SMS_SUBMIT,
    SMS_STATUS_REPORT,
    SMS_COMMAND
}
