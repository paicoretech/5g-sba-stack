package com.paicore.sba.stack.api.n20;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class SmsSubscriptionData {

    private final String supi;
    private final boolean smsSubscribed;
    private final boolean mtSmsSubscribed;
    private final boolean moSmsSubscribed;
    private final boolean mtSmsBarringAll;
    private final boolean moSmsBarringAll;
}
