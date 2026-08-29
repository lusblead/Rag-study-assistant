package com.rag.backend.rerank;

import com.rag.backend.agent.rerank.RerankExecutionResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RerankSettingsServiceTimeoutTest {

    @Test
    void localServerTimeoutIsClassifiedOnceWithoutExternalApi()
            throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        ExecutorService serverExecutor = Executors.newSingleThreadExecutor(
                runnable -> {
                    Thread thread = new Thread(
                            runnable, "rerank-timeout-test-server");
                    thread.setDaemon(true);
                    return thread;
                });
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverExecutor);
        server.createContext("/v1/rerank", exchange -> {
            requestCount.incrementAndGet();
            requestReceived.countDown();
            try {
                releaseResponse.await(5, TimeUnit.SECONDS);
                byte[] body = "{\"results\":[]}".getBytes(
                        StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        try {
            Duration requestTimeout = Duration.ofMillis(100);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(1))
                    .build();
            RerankSettingsService service =
                    RerankSettingsServiceTest.serviceUsing(
                            client, requestTimeout);
            String localBaseUrl = "http://127.0.0.1:"
                    + server.getAddress().getPort() + "/v1";

            RerankExecutionResult.TechnicalFailure failure = assertThrows(
                    RerankExecutionResult.TechnicalFailure.class,
                    () -> service.rerankRemote(
                            "query",
                            RerankSettingsServiceTest.candidates(),
                            2,
                            RerankSettingsServiceTest.remoteSettings(
                                    localBaseUrl)));

            assertEquals(RerankExecutionResult.FailureType.TIMEOUT,
                    failure.failureType());
            assertTrue(requestReceived.await(1, TimeUnit.SECONDS));
            assertEquals(1, requestCount.get());
        } finally {
            releaseResponse.countDown();
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }
}
