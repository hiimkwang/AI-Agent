package com.ai.aiagent.common;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * A plain {@code RestClient.Builder} has no timeout at all, so one unresponsive remote
 * pins its caller thread forever. That is fatal on the Teams path: the bot answers on a
 * small fixed pool, so a few hung calls drain the pool and the bot stops replying without
 * logging a single error.
 */
public final class HttpTimeouts {

    private HttpTimeouts() {
    }

    /** Same value for connect and read - these are all small control-plane calls. */
    public static ClientHttpRequestFactory factory(int seconds) {
        Duration timeout = Duration.ofSeconds(Math.max(1, seconds));
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        return factory;
    }
}
