package com.paicore.sba.stack.api;

import com.paicore.sba.stack.dto.RegistrationRequest;
import com.paicore.sba.stack.dto.RegistrationResponse;

public interface RegistrationApi {

    RegistrationResponse registerUe(RegistrationRequest request);

    void deregisterUe(String supi);
}
