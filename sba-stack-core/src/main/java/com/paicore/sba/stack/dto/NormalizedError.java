package com.paicore.sba.stack.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.paicore.sba.stack.exceptions.SdkException;
import com.paicore.sba.stack.utils.ErrorCodes;
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
public class NormalizedError {

    private String code;
    private String message;
    private String details;

    public static NormalizedError of(String code, String details) {
        return NormalizedError.builder()
                .code(code)
                .message(ErrorCodes.describe(code))
                .details(details)
                .build();
    }

    public static NormalizedError from(SdkException ex) {
        return NormalizedError.builder()
                .code(ex.getCode())
                .message(ex.getMessage())
                .details(ex.getDetails())
                .build();
    }

    @Override
    public String toString() {
        return SdkConverter.valueAsString(this);
    }
}
