package com.paicore.sba.stack.codec;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Generated;
import com.paicore.sba.stack.utils.SdkConverter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Generated
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EncodeTpduRequest {

    private Integer dataCoding;
    private String message;
    private MessageType messageType;
    private String destinationAddress;
    private Integer destinationAddressTon;
    private Integer destinationAddressNpi;
    private String originatingAddress;
    private Integer originatingAddressTon;
    private Integer originatingAddressNpi;
    private Integer protocolId;
    private byte[] userData;
    private Integer userDataLength;
    private boolean userDataHeaderPresent;

    @Override
    public String toString() {
        return SdkConverter.valueAsString(this);
    }
}
