package com.paicore.sba.stack.mo;

/**
 * A decoded {@code multipart/related} body with its JSON root left as text.
 *
 * <p>The typed {@link MoMultipartPayload} only fits the {@code SmsRecordData} root of the Nsmsf
 * MO. A body whose root is another schema — the {@code N1N2MessageTransferReqData} the MT now
 * carries, for one — is read through this, and the caller parses the root it expects.
 *
 * @param contentId content id the JSON root pointed the binary part to, when it declared one
 * @param json the JSON root part, verbatim
 * @param binaryData the binary part
 */
public record RawMultipartPayload(String contentId, String json, byte[] binaryData) {
}
