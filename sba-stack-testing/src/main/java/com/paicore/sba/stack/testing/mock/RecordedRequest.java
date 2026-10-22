package com.paicore.sba.stack.testing.mock;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;

/**
 * Snapshot of an inbound request the mock SBA environment received, so tests
 * can assert on the wire-level interaction without re-implementing parsing.
 */
@Getter
@Builder
public class RecordedRequest {

    private final String method;
    private final String path;
    private final String query;
    private final Map<String, String> headers;
    private final byte[] body;
    private final Instant receivedAt;
    private final int responseStatus;
}
