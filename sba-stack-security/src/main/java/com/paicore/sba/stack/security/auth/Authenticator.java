package com.paicore.sba.stack.security.auth;

import com.paicore.sba.stack.security.exceptions.AuthenticationException;

public interface Authenticator {

    Principal authenticate(String credential) throws AuthenticationException;
}
