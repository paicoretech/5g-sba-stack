package com.paicore.sba.stack.mo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.paicore.sba.stack.openapi.model.SmsRecordData;
import lombok.Generated;
import com.paicore.sba.stack.utils.SdkConverter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Structured view of a 3GPP TS29540 sendsms multipart/related request:
 * the JSON {@link SmsRecordData} root part plus the referenced binary
 * (application/vnd.3gpp.sms) part. The {@code contentId} matches the
 * {@code smsPayload.contentId} field inside {@code jsonData}.
 */
@Getter
@Setter
@Generated
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MoMultipartPayload {

    private String contentId;
    private SmsRecordData jsonData;
    private byte[] binaryData;

    @Override
    public String toString() {
        return SdkConverter.valueAsString(this);
    }
}
