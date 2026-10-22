package com.paicore.sba.stack.security.config;

import com.paicore.sba.stack.config.BaseTlsConfig;
import com.paicore.sba.stack.utils.SdkConverter;
import lombok.AllArgsConstructor;
import lombok.Generated;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@Generated
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TlsConfig extends BaseTlsConfig {

    @lombok.Builder.Default
    private boolean mTlsRequired = false;

    @Override
    public String toString() {
        return SdkConverter.valueAsString(this);
    }
}
