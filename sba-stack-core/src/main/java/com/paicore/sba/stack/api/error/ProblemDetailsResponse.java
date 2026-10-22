package com.paicore.sba.stack.api.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/** RFC 9457 + 3GPP TS 29.571 §5.2.7 ProblemDetails payload. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemDetailsResponse(
        String type,
        String title,
        Integer status,
        String detail,
        String instance,
        String cause,
        String timestamp
) {
}
