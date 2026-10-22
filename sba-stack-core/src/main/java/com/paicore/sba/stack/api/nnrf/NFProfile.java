package com.paicore.sba.stack.api.nnrf;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class NFProfile {

    private final String nfInstanceId;
    private final NFType nfType;
    private final String apiRoot;
}
