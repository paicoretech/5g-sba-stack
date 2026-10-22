package com.paicore.sba.stack.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.paicore.sba.stack.openapi.model.SmsRecordData;
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
public class MoMessageRequest {

    private String correlationId;
    private SmsRecordData smsRecord;

    @Override
    public String toString() {
        return SdkConverter.valueAsString(this);
    }
}
