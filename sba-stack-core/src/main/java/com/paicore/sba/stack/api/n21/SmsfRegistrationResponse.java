package com.paicore.sba.stack.api.n21;

import com.paicore.sba.stack.openapi.model.ProblemDetails;
import com.paicore.sba.stack.openapi.model.SmsfRegistration;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * Typed result of an N21 operation against the UDM.
 *
 * <p>The client hides all HTTP details; this DTO exposes the outcome only:
 * the HTTP status, a success flag, the {@code Location} header (for created
 * registrations), the parsed {@link SmsfRegistration} body (for reads), and a
 * parsed {@link ProblemDetails} when the UDM returned an error body.</p>
 */
@Getter
@Builder
@ToString
public class SmsfRegistrationResponse {

    /** HTTP status code returned by the UDM. */
    private final int statusCode;

    /** {@code true} when the status code is 2xx. */
    private final boolean success;

    /** {@code Location} header of a newly created registration (201), if present. */
    private final String location;

    /** Parsed registration body, when the response carried one. May be {@code null}. */
    private final SmsfRegistration registration;

    /** Parsed RFC 7807 problem details, when the UDM returned an error body. May be {@code null}. */
    private final ProblemDetails problem;

    /** Raw response body, for logging/diagnostics. May be {@code null}. */
    private final String rawBody;
}
