package com.paicore.sba.stack.transport.http2;

import com.paicore.sba.stack.transport.http2.config.ConnectionPoolSettings;
import com.paicore.sba.stack.transport.http2.config.Http2ClientSettings;
import com.paicore.sba.stack.transport.http2.config.TlsSettings;
import com.paicore.sba.stack.utils.SdkConverter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Http2ClientSettingsTest {

    @Test
    void defaultsAreApplied() {
        Http2ClientSettings cfg = Http2ClientSettings.builder().host("amf.example.com").build();
        assertEquals(8443, cfg.getPort());
        assertTrue(cfg.getTls().isEnabled());
        assertTrue(cfg.getTls().getProtocols().contains("TLSv1.3"));
        assertEquals(10_000, cfg.getPool().getMaxConcurrentStreams());
    }

    @Test
    void jsonRoundTrip() {
        Http2ClientSettings cfg = Http2ClientSettings.builder()
                .host("amf.example.com")
                .port(8443)
                .tls(TlsSettings.builder().enabled(true).verifyHostname(true).build())
                .pool(ConnectionPoolSettings.builder().maxConnections(8).build())
                .build();
        String json = SdkConverter.valueAsString(cfg);
        Http2ClientSettings parsed = SdkConverter.stringToObject(json, Http2ClientSettings.class);
        assertEquals(cfg.getHost(), parsed.getHost());
        assertEquals(cfg.getPort(), parsed.getPort());
        assertEquals(cfg.getPool().getMaxConnections(), parsed.getPool().getMaxConnections());
    }
}
