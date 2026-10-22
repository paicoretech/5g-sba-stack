package com.paicore.sba.stack.dto;

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
public class MoResponse {

    private String correlationId;
    private String smsRecordId;
    private boolean accepted;
    private NormalizedError error;

    @Override
    public String toString() {
        return SdkConverter.valueAsString(this);
    }
}
