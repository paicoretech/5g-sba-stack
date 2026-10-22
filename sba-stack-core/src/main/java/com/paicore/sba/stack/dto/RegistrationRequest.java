package com.paicore.sba.stack.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.paicore.sba.stack.openapi.model.UeSmsContextData;
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
public class RegistrationRequest {

    private String correlationId;
    private UeSmsContextData ueContext;

    @Override
    public String toString() {
        return SdkConverter.valueAsString(this);
    }
}
