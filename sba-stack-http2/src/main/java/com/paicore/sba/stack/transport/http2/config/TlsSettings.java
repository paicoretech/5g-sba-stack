package com.paicore.sba.stack.transport.http2.config;

import com.paicore.sba.stack.config.BaseTlsConfig;
import com.paicore.sba.stack.utils.SdkConverter;
import lombok.AllArgsConstructor;
import lombok.Generated;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.List;

@Getter
@Setter
@Generated
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TlsSettings extends BaseTlsConfig {

    @lombok.Builder.Default
    private boolean enabled = true;

    @lombok.Builder.Default
    private boolean verifyHostname = true;

    private List<String> cipherSuites;

    @Override
    public String toString() {
        return SdkConverter.valueAsString(this);
    }
}
