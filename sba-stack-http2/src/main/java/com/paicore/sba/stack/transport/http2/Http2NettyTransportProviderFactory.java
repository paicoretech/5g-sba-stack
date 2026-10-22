package com.paicore.sba.stack.transport.http2;

import com.paicore.sba.stack.api.config.Http2Config;
import com.paicore.sba.stack.spi.TransportProvider;
import com.paicore.sba.stack.spi.TransportProviderFactory;
import com.paicore.sba.stack.transport.http2.config.Http2ClientSettings;

/**
 * ServiceLoader-registered factory that builds a Netty-backed
 * {@link TransportProvider} when the supplied config is an
 * {@link Http2ClientSettings}; returns {@code null} otherwise so the
 * stack can be built for unit tests without a concrete transport.
 */
public class Http2NettyTransportProviderFactory implements TransportProviderFactory {

    @Override
    public TransportProvider create(Http2Config config) {
        if (!(config instanceof Http2ClientSettings settings)) {
            return null;
        }
        return new Http2RequestDispatcher(new NettyHttp2Client(settings));
    }
}
