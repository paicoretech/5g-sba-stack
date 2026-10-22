package com.paicore.sba.stack.api.nnrf;

import java.util.List;

public interface NrfClient {

    List<NFProfile> discoverInstances(NFType targetNfType);
}
