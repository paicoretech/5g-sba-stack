package com.paicore.sba.stack.spi;

import com.paicore.sba.stack.api.SbaStack;
import com.paicore.sba.stack.api.SbaStackImpl;
import com.paicore.sba.stack.api.config.DiscoveryConfig;
import com.paicore.sba.stack.api.config.Http2Config;
import com.paicore.sba.stack.api.config.SecurityConfig;

public class DefaultSbaStackProvider implements SbaStackProvider {

    @Override
    public SbaStack create(Http2Config http2, SecurityConfig security, DiscoveryConfig discovery) {
        return new SbaStackImpl(http2, security, discovery);
    }
}
