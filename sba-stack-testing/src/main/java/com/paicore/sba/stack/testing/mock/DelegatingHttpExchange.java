package com.paicore.sba.stack.testing.mock;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;

/**
 * Thin wrapper around {@link HttpExchange} so route handlers can be passed an
 * exchange whose body has already been read into a byte[]. The subclass
 * {@link MockSbaCoreServer.ExchangeWithBody} stores that body alongside the
 * underlying exchange so handlers can call {@code bodyOf(exchange)} more than
 * once without re-reading the request stream.
 */
abstract class DelegatingHttpExchange extends HttpExchange {

    protected final HttpExchange delegate;

    protected DelegatingHttpExchange(HttpExchange delegate) {
        this.delegate = delegate;
    }

    @Override public Headers getRequestHeaders() { return delegate.getRequestHeaders(); }
    @Override public Headers getResponseHeaders() { return delegate.getResponseHeaders(); }
    @Override public URI getRequestURI() { return delegate.getRequestURI(); }
    @Override public String getRequestMethod() { return delegate.getRequestMethod(); }
    @Override public HttpContext getHttpContext() { return delegate.getHttpContext(); }
    @Override public void close() { delegate.close(); }
    @Override public InputStream getRequestBody() {
        if (this instanceof MockSbaCoreServer.ExchangeWithBody bodied) {
            return new ByteArrayInputStream(bodied.body());
        }
        return delegate.getRequestBody();
    }
    @Override public OutputStream getResponseBody() { return delegate.getResponseBody(); }
    @Override public void sendResponseHeaders(int rCode, long responseLength) throws IOException {
        delegate.sendResponseHeaders(rCode, responseLength);
    }
    @Override public InetSocketAddress getRemoteAddress() { return delegate.getRemoteAddress(); }
    @Override public int getResponseCode() { return delegate.getResponseCode(); }
    @Override public InetSocketAddress getLocalAddress() { return delegate.getLocalAddress(); }
    @Override public String getProtocol() { return delegate.getProtocol(); }
    @Override public Object getAttribute(String name) { return delegate.getAttribute(name); }
    @Override public void setAttribute(String name, Object value) { delegate.setAttribute(name, value); }
    @Override public void setStreams(InputStream i, OutputStream o) { delegate.setStreams(i, o); }
    @Override public HttpPrincipal getPrincipal() { return delegate.getPrincipal(); }
}
