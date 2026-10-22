package com.paicore.sba.stack.testing.mock;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link DelegatingHttpExchange} forwards every accessor /
 * mutator to the wrapped HttpExchange, and that
 * {@link MockSbaCoreServer.ExchangeWithBody} replaces the request stream with
 * the cached body so handlers can read it multiple times.
 */
class DelegatingHttpExchangeTest {

    private HttpExchange delegate;
    private MockSbaCoreServer.ExchangeWithBody wrapped;

    @BeforeEach
    void setup() {
        delegate = mock(HttpExchange.class);
        wrapped = new MockSbaCoreServer.ExchangeWithBody(delegate, "cached".getBytes());
    }

    @Test
    @DisplayName("getRequestBody returns a fresh stream over the cached body")
    void requestBodyReturnsCachedBytes() throws IOException {
        InputStream stream = wrapped.getRequestBody();
        assertThat(stream).isInstanceOf(ByteArrayInputStream.class);
        assertThat(stream.readAllBytes()).isEqualTo("cached".getBytes());
        // re-reading is safe because the wrapper always creates a new stream
        assertThat(wrapped.getRequestBody().readAllBytes()).isEqualTo("cached".getBytes());
    }

    @Test
    @DisplayName("all accessors delegate to the wrapped HttpExchange")
    void accessorsDelegate() {
        Headers reqHeaders = new Headers();
        Headers respHeaders = new Headers();
        URI uri = URI.create("/foo");
        HttpContext ctx = mock(HttpContext.class);
        InetSocketAddress remote = new InetSocketAddress("1.2.3.4", 1234);
        InetSocketAddress local = new InetSocketAddress("127.0.0.1", 0);
        HttpPrincipal principal = mock(HttpPrincipal.class);

        when(delegate.getRequestHeaders()).thenReturn(reqHeaders);
        when(delegate.getResponseHeaders()).thenReturn(respHeaders);
        when(delegate.getRequestURI()).thenReturn(uri);
        when(delegate.getRequestMethod()).thenReturn("POST");
        when(delegate.getHttpContext()).thenReturn(ctx);
        when(delegate.getRemoteAddress()).thenReturn(remote);
        when(delegate.getLocalAddress()).thenReturn(local);
        when(delegate.getResponseCode()).thenReturn(201);
        when(delegate.getProtocol()).thenReturn("HTTP/1.1");
        when(delegate.getAttribute("x")).thenReturn("v");
        when(delegate.getPrincipal()).thenReturn(principal);

        assertThat(wrapped.getRequestHeaders()).isSameAs(reqHeaders);
        assertThat(wrapped.getResponseHeaders()).isSameAs(respHeaders);
        assertThat(wrapped.getRequestURI()).isEqualTo(uri);
        assertThat(wrapped.getRequestMethod()).isEqualTo("POST");
        assertThat(wrapped.getHttpContext()).isSameAs(ctx);
        assertThat(wrapped.getRemoteAddress()).isSameAs(remote);
        assertThat(wrapped.getLocalAddress()).isSameAs(local);
        assertThat(wrapped.getResponseCode()).isEqualTo(201);
        assertThat(wrapped.getProtocol()).isEqualTo("HTTP/1.1");
        assertThat(wrapped.getAttribute("x")).isEqualTo("v");
        assertThat(wrapped.getPrincipal()).isSameAs(principal);
    }

    @Test
    @DisplayName("mutators (close / setAttribute / setStreams / sendResponseHeaders) delegate too")
    void mutatorsDelegate() throws IOException {
        OutputStream out = mock(OutputStream.class);
        InputStream in = new ByteArrayInputStream(new byte[0]);
        when(delegate.getResponseBody()).thenReturn(out);
        doNothing().when(delegate).sendResponseHeaders(anyInt(), anyLong());

        wrapped.sendResponseHeaders(204, 0);
        wrapped.setAttribute("k", "v");
        wrapped.setStreams(in, out);
        wrapped.close();
        assertThat(wrapped.getResponseBody()).isSameAs(out);

        verify(delegate).sendResponseHeaders(204, 0);
        verify(delegate).setAttribute("k", "v");
        verify(delegate).setStreams(in, out);
        verify(delegate).close();
        verify(delegate).getResponseBody();
    }

    @Test
    @DisplayName("non-cached DelegatingHttpExchange variant returns delegate.getRequestBody()")
    void nonBodiedVariantFallsThroughToDelegate() {
        DelegatingHttpExchange anonymous = new DelegatingHttpExchange(delegate) { };
        InputStream stream = new ByteArrayInputStream("via-delegate".getBytes());
        when(delegate.getRequestBody()).thenReturn(stream);
        assertThat(anonymous.getRequestBody()).isSameAs(stream);
    }
}
