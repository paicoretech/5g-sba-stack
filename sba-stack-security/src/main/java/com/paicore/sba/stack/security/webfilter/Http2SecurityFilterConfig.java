package com.paicore.sba.stack.security.webfilter;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Objects;

@Getter
@Builder
public class Http2SecurityFilterConfig {

    private final boolean securityEnabled;
    private final String sbiServerBasePath;
    private final List<String> bypassPaths;
    private final String bearerHeaderName;
    private final String bearerScheme;

    public List<String> getBypassPaths() {
        return Objects.requireNonNullElse(bypassPaths, List.of());
    }
}
