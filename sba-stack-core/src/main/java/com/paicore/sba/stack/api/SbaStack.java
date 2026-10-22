package com.paicore.sba.stack.api;

import com.paicore.sba.stack.api.n20.N20Client;
import com.paicore.sba.stack.api.nnrf.NrfClient;

public interface SbaStack extends RegistrationApi, MoApi, MtApi, DlrApi, AutoCloseable {

    String version();

    N20Client getN20Client();

    NrfClient getNrfClient();

    static SbaStackBuilder builder() {
        return new SbaStackBuilder();
    }

    @Override
    void close();
}
