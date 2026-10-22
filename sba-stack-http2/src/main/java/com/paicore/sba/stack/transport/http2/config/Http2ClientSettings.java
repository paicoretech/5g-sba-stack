package com.paicore.sba.stack.transport.http2.config;

import com.paicore.sba.stack.api.config.Http2Config;
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
public class Http2ClientSettings implements Http2Config {

    private String key;
    private String host;
    private String path;

    @Builder.Default
    private int port = 8443;

    @Builder.Default
    private String userAgent = "sba-stack-http2/1.0.0-SNAPSHOT";

    @Builder.Default
    private TlsSettings tls = TlsSettings.builder().build();

    @Builder.Default
    private ConnectionPoolSettings pool = ConnectionPoolSettings.builder().build();

    @Override
    public String toString() {
        return SdkConverter.valueAsString(this);
    }
}
