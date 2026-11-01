package com.sentinelpay.common.idempotency;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Reads the request body exactly once so the idempotency filter can fingerprint it
 * and the controller can still consume it afterwards.
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    public CachedBodyHttpServletRequest(HttpServletRequest request, int maxBodyBytes) throws IOException {
        super(request);
        byte[] candidate = request.getInputStream().readNBytes(maxBodyBytes + 1);
        if (candidate.length > maxBodyBytes) {
            throw new RequestBodyTooLargeException(maxBodyBytes);
        }
        this.body = candidate;
    }

    public byte[] body() {
        return body.clone();
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream source = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return source.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                throw new UnsupportedOperationException("Async request bodies are not supported");
            }

            @Override
            public int read() {
                return source.read();
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                return source.read(buffer, offset, length);
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }

    public static class RequestBodyTooLargeException extends IOException {
        public RequestBodyTooLargeException(int maxBodyBytes) {
            super("Request body exceeds the configured " + maxBodyBytes + " byte limit");
        }
    }
}
