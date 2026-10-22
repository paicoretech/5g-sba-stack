package com.paicore.sba.stack.api;

import com.paicore.sba.stack.dto.MtMessageRequest;
import com.paicore.sba.stack.dto.MtResponse;

public interface MtApi {

    MtResponse sendMtMessage(MtMessageRequest request);
}
