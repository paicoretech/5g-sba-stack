package com.paicore.sba.stack.transport.http2;

import com.paicore.sba.stack.exceptions.Http2TransportException;
import com.paicore.sba.stack.tls.PemKeyStoreLoader;
import com.paicore.sba.stack.transport.http2.config.Http2ClientSettings;
import com.paicore.sba.stack.transport.http2.config.TlsSettings;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.RequiredArgsConstructor;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;
import java.util.Objects;
import java.util.function.Consumer;

@RequiredArgsConstructor
public class Http2ClientChannelInitializer extends ChannelInitializer<Channel> {

    private static final String EMPTY_PASSWORD = "";

    private final Http2ClientSettings settings;
    private final Consumer<Http2StreamHandler> streamHandlerSink;

    @Override
    protected void initChannel(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        TlsSettings tls = settings.getTls();
        if (Objects.nonNull(tls) && tls.isEnabled()) {
            SslContext sslContext = buildSslContext(tls);
            SSLEngine engine = sslContext.newEngine(channel.alloc(),
                    settings.getHost(), settings.getPort());
            if (tls.isVerifyHostname()) {
                SSLParameters params = engine.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                engine.setSSLParameters(params);
            }
            pipeline.addLast(new SslHandler(engine));
        }
        Http2FrameCodec codec = Http2FrameCodecBuilder.forClient().build();
        pipeline.addLast(codec);
        Http2StreamHandler streamHandler = new Http2StreamHandler();
        pipeline.addLast(streamHandler);
        if (Objects.nonNull(streamHandlerSink)) {
            streamHandlerSink.accept(streamHandler);
        }
    }

    SslContext buildSslContext(TlsSettings tls) {
        try {
            SslContextBuilder builder = SslContextBuilder.forClient()
                    .sslProvider(SslProvider.JDK)
                    .applicationProtocolConfig(new ApplicationProtocolConfig(
                            ApplicationProtocolConfig.Protocol.ALPN,
                            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                            ApplicationProtocolNames.HTTP_2,
                            ApplicationProtocolNames.HTTP_1_1))
                    .ciphers(tls.getCipherSuites(), SupportedCipherSuiteFilter.INSTANCE);

            applyTrustMaterial(builder, tls);
            applyKeyMaterial(builder, tls);

            if (tls.getProtocols() != null && !tls.getProtocols().isEmpty()) {
                builder.protocols(tls.getProtocols().toArray(new String[0]));
            }
            return builder.build();
        } catch (Exception e) {
            throw new Http2TransportException("Failed to build SslContext", e);
        }
    }

    private void applyTrustMaterial(SslContextBuilder builder, TlsSettings tls) throws Exception {
        String trustPath = tls.getTrustStorePath();
        if (Objects.nonNull(trustPath) && !trustPath.isBlank()) {
            KeyStore trustStore = PemKeyStoreLoader.loadTrustStore(trustPath);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            builder.trustManager(tmf);
            return;
        }
        if (!tls.isVerifyHostname()) {
            builder.trustManager(InsecureTrustManagerFactory.INSTANCE);
        }
    }

    private void applyKeyMaterial(SslContextBuilder builder, TlsSettings tls) throws Exception {
        String certPath = tls.getKeyStorePath();
        String keyPath = tls.getKeyPath();
        if (Objects.isNull(certPath) || certPath.isBlank()
                || Objects.isNull(keyPath) || keyPath.isBlank()) {
            return;
        }
        String password = tls.getKeyStorePassword();
        KeyStore keyStore = PemKeyStoreLoader.loadKeyStore(certPath, keyPath,
                Objects.isNull(password) ? EMPTY_PASSWORD : password);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, EMPTY_PASSWORD.toCharArray());
        builder.keyManager(kmf);
    }
}
