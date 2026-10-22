package com.paicore.sba.stack.api.n20;

import com.paicore.sba.stack.validation.SupiValidator;

public class DefaultN20Client implements N20Client {

    public DefaultN20Client() {
        // No collaborators yet: this client returns a static SmsSubscriptionData
        // stub. Transport/security/discovery wiring will be reintroduced when
        // the real Nudm_SDM lookup is implemented.
    }

    @Override
    public SmsSubscriptionData getSmsSubscriptionData(String supi) {
        SupiValidator.requireValid(supi);
        return SmsSubscriptionData.builder()
                .supi(supi)
                .smsSubscribed(true)
                .mtSmsSubscribed(true)
                .moSmsSubscribed(true)
                .build();
    }
}
