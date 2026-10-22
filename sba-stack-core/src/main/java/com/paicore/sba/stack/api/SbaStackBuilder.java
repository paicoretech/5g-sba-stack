package com.paicore.sba.stack.api;

import com.paicore.sba.stack.api.config.DiscoveryConfig;
import com.paicore.sba.stack.api.config.Http2Config;
import com.paicore.sba.stack.api.config.SecurityConfig;
import com.paicore.sba.stack.exceptions.SdkException;
import com.paicore.sba.stack.spi.SbaStackProvider;
import com.paicore.sba.stack.utils.ErrorCodes;

import java.util.Iterator;
import java.util.ServiceLoader;

public final class SbaStackBuilder {

    private Http2Config http2;
    private SecurityConfig security;
    private DiscoveryConfig discovery;

    SbaStackBuilder() {
    }

    public SbaStackBuilder http2(Http2Config http2) {
        this.http2 = http2;
        return this;
    }

    public SbaStackBuilder security(SecurityConfig security) {
        this.security = security;
        return this;
    }

    public SbaStackBuilder discovery(DiscoveryConfig discovery) {
        this.discovery = discovery;
        return this;
    }

    public SbaStack build() {
        Iterator<SbaStackProvider> providers = ServiceLoader.load(SbaStackProvider.class).iterator();
        if (!providers.hasNext()) {
            throw new SdkException(ErrorCodes.INTERNAL_UNEXPECTED,
                    "No SbaStackProvider registered",
                    "ServiceLoader could not find a META-INF/services/com.paicore.sba.stack.spi.SbaStackProvider entry");
        }
        return providers.next().create(http2, security, discovery);
    }
}
