package com.paicore.sba.stack.transport.http2;

import com.paicore.sba.stack.api.config.Http2Config;
import com.paicore.sba.stack.spi.TransportProvider;
import com.paicore.sba.stack.transport.http2.config.Http2ClientSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Http2NettyTransportProviderFactoryTest {

    private final Http2NettyTransportProviderFactory factory = new Http2NettyTransportProviderFactory();

    @Test
    @DisplayName("create returns null when config is null")
    void returnsNullForNullConfig() {
        assertThat(factory.create(null)).isNull();
    }

    @Test
    @DisplayName("create returns null when config is an unknown Http2Config marker")
    void returnsNullForUnknownMarker() {
        Http2Config opaque = new Http2Config() {};
        assertThat(factory.create(opaque)).isNull();
    }

    @Test
    @DisplayName("create returns a Netty-backed TransportProvider for Http2ClientSettings")
    void returnsRealTransportForSettings() {
        Http2ClientSettings settings = Http2ClientSettings.builder()
                .host("127.0.0.1")
                .port(18443)
                .build();
        TransportProvider provider = factory.create(settings);
        try {
            assertThat(provider).isNotNull().isInstanceOf(Http2RequestDispatcher.class);
        } finally {
            if (provider != null) {
                provider.close();
            }
        }
    }
}
