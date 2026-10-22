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
public class SmsPayload {

    private String contentId;
    private String content;
    private String encoding;
    private Integer dataCodingScheme;

    @Override
    public String toString() {
        return SdkConverter.valueAsString(this);
    }
}
