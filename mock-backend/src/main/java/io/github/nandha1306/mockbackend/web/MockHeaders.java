package io.github.nandha1306.mockbackend.web;

/**
 * HTTP header names the mock backend reads and writes.
 */
public final class MockHeaders {
    public static final String CLIENT_ID = "X-Client-Id";
    public static final String INSTANCE_ID = "X-Instance-Id";
    public static final String CACHE = "X-Cache";

    private MockHeaders() {
    }
}
