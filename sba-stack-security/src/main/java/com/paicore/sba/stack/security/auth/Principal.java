package com.paicore.sba.stack.security.auth;

import lombok.Generated;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.Set;

@Getter
@Generated
@Builder
@AllArgsConstructor
public class Principal {

    private final String id;
    private final String issuer;
    private final Set<String> scopes;
}
