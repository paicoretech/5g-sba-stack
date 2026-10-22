package com.paicore.sba.stack.transport.http2.config;

import com.paicore.sba.stack.transport.http2.util.Http2Constants;
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
public class ConnectionPoolSettings {

    @Builder.Default
    private int maxConnections = 16;

    @Builder.Default
    private int maxConcurrentStreams = Http2Constants.DEFAULT_MAX_CONCURRENT_STREAMS;

    @Builder.Default
    private int idleTimeoutSeconds = 60;

    @Builder.Default
    private int connectTimeoutMillis = Http2Constants.DEFAULT_CONNECT_TIMEOUT_MS;

    @Builder.Default
    private int readTimeoutMillis = Http2Constants.DEFAULT_READ_TIMEOUT_MS;

    @Override
    public String toString() {
        return SdkConverter.valueAsString(this);
    }
}
