package com.paicore.sba.stack.spi;

import com.paicore.sba.stack.api.SbaStack;
import com.paicore.sba.stack.api.config.DiscoveryConfig;
import com.paicore.sba.stack.api.config.Http2Config;
import com.paicore.sba.stack.api.config.SecurityConfig;

public interface SbaStackProvider {

    SbaStack create(Http2Config http2, SecurityConfig security, DiscoveryConfig discovery);
}
