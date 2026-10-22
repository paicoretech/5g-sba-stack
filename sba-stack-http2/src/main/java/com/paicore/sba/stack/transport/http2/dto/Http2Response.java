package com.paicore.sba.stack.transport.http2.dto;

import lombok.Generated;
import com.paicore.sba.stack.utils.SdkConverter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@Generated
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Http2Response {

    private int status;

    @Builder.Default
    private Map<String, String> headers = new HashMap<>();

    private byte[] body;
    private String correlationId;

    @Override
    public String toString() {
        return SdkConverter.valueAsString(this);
    }
}
