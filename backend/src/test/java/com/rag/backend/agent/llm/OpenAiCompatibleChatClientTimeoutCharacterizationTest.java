package com.rag.backend.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.agent.settings.AgentModelSettings;
import com.rag.backend.agent.settings.AgentModelSettingsService;
import com.rag.backend.common.BizException;
import com.rag.backend.observability.trace.TraceContextService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.Disposable;
import reactor.core.publisher.Signal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
// 冻结 OpenAI-compatible 客户端当前超时分类、外部消息、单次请求和敏感信息边界。
class OpenAiCompatibleChatClientTimeoutCharacterizationTest {
    private static final String TEST_API_KEY = "test-api-key-must-not-leak";
    private static final String TEST_PROMPT = "test-prompt-must-not-leak";
    private static final String FULL_MODEL_RESPONSE = "complete-model-response-must-not-leak";

    @Test
    // 同步 request timeout 当前被包装为 code=500 的 BizException，且没有内建重试。
    void currentSynchronousRequestTimeoutIsWrappedOnceWithoutLeakingSensitiveData(CapturedOutput output)
            throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpTimeoutException timeout = new HttpTimeoutException("request timed out");
        failSend(httpClient, timeout);
        ClientFixture fixture = clientUsing(httpClient);

        BizException thrown = assertThrowsBizException(() -> fixture.client().call(TEST_PROMPT));

        assertEquals(500, thrown.getCode());
        assertEquals("LLM request failed: request timed out", thrown.getMessage());
        verifySingleSend(httpClient);
        verify(fixture.settingsService(), times(1)).currentSettings();
        assertSensitiveDataAbsent(thrown, output);
    }

    @Test
    // 同步 read timeout 当前同样按 IOException 路径包装，外部消息保留底层超时文本。
    void currentSynchronousReadTimeoutIsWrappedOnceWithoutLeakingSensitiveData(CapturedOutput output)
            throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        SocketTimeoutException timeout = new SocketTimeoutException("Read timed out");
        failSend(httpClient, timeout);
        ClientFixture fixture = clientUsing(httpClient);

        BizException thrown = assertThrowsBizException(() -> fixture.client().call(TEST_PROMPT));

        assertEquals(500, thrown.getCode());
        assertEquals("LLM request failed: Read timed out", thrown.getMessage());
        verifySingleSend(httpClient);
        verify(fixture.settingsService(), times(1)).currentSettings();
        assertSensitiveDataAbsent(thrown, output);
    }

    @Test
    // 同步 connect timeout 是 request timeout 的特例，当前仍走相同 BizException 包装路径。
    void currentSynchronousConnectTimeoutIsWrappedOnceWithoutLeakingSensitiveData(CapturedOutput output)
            throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpConnectTimeoutException timeout = new HttpConnectTimeoutException("HTTP connect timed out");
        failSend(httpClient, timeout);
        ClientFixture fixture = clientUsing(httpClient);

        BizException thrown = assertThrowsBizException(() -> fixture.client().call(TEST_PROMPT));

        assertEquals(500, thrown.getCode());
        assertEquals("LLM request failed: HTTP connect timed out", thrown.getMessage());
        verifySingleSend(httpClient);
        verify(fixture.settingsService(), times(1)).currentSettings();
        assertSensitiveDataAbsent(thrown, output);
    }

    @Test
    // 流式 request timeout 当前不转换为 BizException，而是原样进入 Flux 错误信号。
    void currentStreamingRequestTimeoutRemainsOriginalErrorAndIsSentOnce(CapturedOutput output)
            throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpTimeoutException timeout = new HttpTimeoutException("request timed out");
        failSendAsync(httpClient, timeout);
        ClientFixture fixture = clientUsing(httpClient);

        Signal<String> terminal = fixture.client().stream(TEST_PROMPT)
                .materialize()
                .blockLast(Duration.ofSeconds(5));

        assertNotNull(terminal);
        assertTrue(terminal.isOnError());
        assertSame(timeout, terminal.getThrowable());
        assertFalse(terminal.getThrowable() instanceof BizException);
        verifySingleSendAsync(httpClient);
        verify(fixture.settingsService(), times(1)).currentSettings();
        assertSensitiveDataAbsent(terminal.getThrowable(), output);
    }

    @Test
    // 流式响应读取中断时，当前保留 UncheckedIOException/SocketTimeoutException 异常链且不重试。
    void currentStreamingReadTimeoutRemainsOriginalErrorAndIsSentOnce(CapturedOutput output)
            throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<Stream<String>> response = successfulStreamingResponseThatTimesOutWhileReading();
        returnResponseAsync(httpClient, response);
        ClientFixture fixture = clientUsing(httpClient);

        Signal<String> terminal = fixture.client().stream(TEST_PROMPT)
                .materialize()
                .blockLast(Duration.ofSeconds(5));

        assertNotNull(terminal);
        assertTrue(terminal.isOnError());
        UncheckedIOException readFailure = assertInstanceOf(
                UncheckedIOException.class,
                terminal.getThrowable()
        );
        SocketTimeoutException timeout = assertInstanceOf(
                SocketTimeoutException.class,
                readFailure.getCause()
        );
        assertEquals("Read timed out", timeout.getMessage());
        verifySingleSendAsync(httpClient);
        verify(fixture.settingsService(), times(1)).currentSettings();
        assertSensitiveDataAbsent(readFailure, output);
    }

    @Test
    void synchronousProviderErrorOmitsResponseBodyFromExceptionAndLogs(
            CapturedOutput output) throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(502);
        when(response.body()).thenReturn(FULL_MODEL_RESPONSE);
        doReturn(response).when(httpClient)
                .send(any(HttpRequest.class),
                        any(HttpResponse.BodyHandler.class));
        ClientFixture fixture = clientUsing(httpClient);

        BizException thrown = assertThrowsBizException(
                () -> fixture.client().call(TEST_PROMPT));

        assertEquals(500, thrown.getCode());
        assertEquals("LLM request failed, status=502", thrown.getMessage());
        verifySingleSend(httpClient);
        assertSensitiveDataAbsent(thrown, output);
    }

    @Test
    void streamingProviderErrorOmitsAndDoesNotReadBodyThenClosesIt(
            CapturedOutput output) {
        HttpClient httpClient = mock(HttpClient.class);
        AtomicBoolean closed = new AtomicBoolean();
        Stream<String> responseLines = Stream.<String>generate(() -> {
            throw new AssertionError("provider error body must not be read");
        }).onClose(() -> closed.set(true));
        @SuppressWarnings("unchecked")
        HttpResponse<Stream<String>> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(429);
        when(response.body()).thenReturn(responseLines);
        returnResponseAsync(httpClient, response);
        ClientFixture fixture = clientUsing(httpClient);

        Signal<String> terminal = fixture.client().stream(TEST_PROMPT)
                .materialize()
                .blockLast(Duration.ofSeconds(5));

        assertNotNull(terminal);
        assertTrue(terminal.isOnError());
        BizException failure = assertInstanceOf(
                BizException.class, terminal.getThrowable());
        assertEquals("LLM stream request failed, status=429",
                failure.getMessage());
        assertTrue(closed.get());
        verifySingleSendAsync(httpClient);
        assertSensitiveDataAbsent(failure, output);
    }

    @Test
    void cancellingBeforeHeadersCancelsExchangeOnceAndEndsTraceAsCancelled()
            throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        RecordingFuture<HttpResponse<Stream<String>>> pending =
                new RecordingFuture<>();
        CountDownLatch requestStarted = new CountDownLatch(1);
        returnPendingAsync(httpClient, pending, requestStarted);
        List<TraceContextService.TraceEvent> events =
                new CopyOnWriteArrayList<>();
        CountDownLatch traceEnded = new CountDownLatch(1);
        ClientFixture fixture = clientUsing(
                httpClient, traceService(events, traceEnded));
        AtomicReference<Throwable> downstreamError = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean();

        Disposable subscription = fixture.client().stream(TEST_PROMPT)
                .subscribe(ignored -> { }, downstreamError::set,
                        () -> completed.set(true));
        assertTrue(requestStarted.await(5, TimeUnit.SECONDS));
        subscription.dispose();

        assertTrue(pending.cancelled.await(5, TimeUnit.SECONDS));
        assertTrue(traceEnded.await(5, TimeUnit.SECONDS));
        assertEquals(1, pending.cancelCalls.get());
        assertTrue(pending.mayInterrupt.get());
        assertNull(downstreamError.get());
        assertFalse(completed.get());
        assertEquals("cancelled", llmHttpEnd(events).result());
        verifySingleSendAsync(httpClient);
    }

    @Test
    void cancellingAfterHeadersClosesResponseLinesAndEndsTraceAsCancelled()
            throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        CountDownLatch responseClosed = new CountDownLatch(1);
        Stream<String> responseLines = Stream.generate(() -> {
            readStarted.countDown();
            try {
                releaseRead.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("stream read interrupted");
            }
            return "data: {\"choices\":[{\"delta\":{\"content\":\"ignored\"}}]}";
        }).onClose(() -> {
            responseClosed.countDown();
            releaseRead.countDown();
        });
        @SuppressWarnings("unchecked")
        HttpResponse<Stream<String>> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(responseLines);
        returnResponseAsync(httpClient, response);
        List<TraceContextService.TraceEvent> events =
                new CopyOnWriteArrayList<>();
        CountDownLatch traceEnded = new CountDownLatch(1);
        ClientFixture fixture = clientUsing(
                httpClient, traceService(events, traceEnded));
        AtomicReference<Throwable> downstreamError = new AtomicReference<>();

        Disposable subscription = fixture.client().stream(TEST_PROMPT)
                .subscribe(ignored -> { }, downstreamError::set);
        assertTrue(readStarted.await(5, TimeUnit.SECONDS));
        subscription.dispose();

        assertTrue(responseClosed.await(5, TimeUnit.SECONDS));
        assertTrue(traceEnded.await(5, TimeUnit.SECONDS));
        assertNull(downstreamError.get());
        assertEquals("cancelled", llmHttpEnd(events).result());
        verifySingleSendAsync(httpClient);
    }

    private ClientFixture clientUsing(HttpClient httpClient) {
        return clientUsing(httpClient, new TraceContextService());
    }

    private ClientFixture clientUsing(
            HttpClient httpClient,
            TraceContextService traces) {
        AgentModelSettings settings = new AgentModelSettings();
        settings.setLlmProvider("openai-compatible");
        settings.setLlmBaseUrl("http://not-used.invalid/v1");
        settings.setLlmModel("test-model");
        settings.setLlmApiKey(TEST_API_KEY);

        AgentModelSettingsService settingsService = mock(AgentModelSettingsService.class);
        when(settingsService.currentSettings()).thenReturn(settings);
        OpenAiCompatibleChatClient client = new OpenAiCompatibleChatClient(
                httpClient,
                new ObjectMapper(),
                settingsService,
                "test-system-prompt",
                0.2,
                false,
                "",
                1,
                traces
        );
        return new ClientFixture(client, settingsService);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void failSend(HttpClient httpClient, IOException failure) throws Exception {
        doThrow(failure).when(httpClient)
                .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void failSendAsync(HttpClient httpClient, Throwable failure) {
        CompletableFuture<HttpResponse<Stream<String>>> pending =
                new CompletableFuture<>();
        pending.completeExceptionally(failure);
        doReturn(pending).when(httpClient)
                .sendAsync(any(HttpRequest.class),
                        any(HttpResponse.BodyHandler.class));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void returnResponseAsync(
            HttpClient httpClient,
            HttpResponse<Stream<String>> response) {
        doReturn(CompletableFuture.completedFuture(response)).when(httpClient)
                .sendAsync(any(HttpRequest.class),
                        any(HttpResponse.BodyHandler.class));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void returnPendingAsync(
            HttpClient httpClient,
            CompletableFuture<HttpResponse<Stream<String>>> pending,
            CountDownLatch requestStarted) {
        doAnswer(ignored -> {
            requestStarted.countDown();
            return pending;
        }).when(httpClient).sendAsync(
                any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void verifySingleSend(HttpClient httpClient) throws Exception {
        verify(httpClient, times(1))
                .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void verifySingleSendAsync(HttpClient httpClient) {
        verify(httpClient, times(1))
                .sendAsync(any(HttpRequest.class),
                        any(HttpResponse.BodyHandler.class));
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<Stream<String>> successfulStreamingResponseThatTimesOutWhileReading() {
        HttpResponse<Stream<String>> response = mock(HttpResponse.class);
        Stream<String> responseLines = Stream.concat(
                Stream.of("data: {\"choices\":[{\"delta\":{\"content\":\""
                        + FULL_MODEL_RESPONSE + "\"}}]}"),
                Stream.generate(() -> {
                    throw new UncheckedIOException(new SocketTimeoutException("Read timed out"));
                })
        );
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(responseLines);
        return response;
    }

    private BizException assertThrowsBizException(ThrowingCall call) {
        try {
            call.run();
            fail("Expected BizException");
            return null;
        } catch (BizException exception) {
            return exception;
        } catch (Exception exception) {
            throw new AssertionError("Expected BizException but caught " + exception, exception);
        }
    }

    private void assertSensitiveDataAbsent(Throwable failure, CapturedOutput output) {
        String errorText = throwableText(failure);
        String logText = output.getAll();
        for (String sensitive : new String[]{TEST_API_KEY, TEST_PROMPT, FULL_MODEL_RESPONSE}) {
            assertFalse(errorText.contains(sensitive), "错误不应包含测试敏感数据");
            assertFalse(logText.contains(sensitive), "日志不应包含测试敏感数据");
        }
    }

    private String throwableText(Throwable failure) {
        StringBuilder text = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            text.append(current.getClass().getName()).append(':').append(current.getMessage()).append('\n');
            current = current.getCause();
        }
        return text.toString();
    }

    private TraceContextService traceService(
            List<TraceContextService.TraceEvent> events,
            CountDownLatch traceEnded) {
        AtomicInteger spans = new AtomicInteger();
        return TraceContextService.forTesting(
                new TraceContextService.IdGenerator() {
                    @Override
                    public String nextTraceId() {
                        return "a".repeat(32);
                    }

                    @Override
                    public String nextSpanId() {
                        return "%016x".formatted(spans.incrementAndGet());
                    }
                },
                event -> {
                    events.add(event);
                    if (event.type() == TraceContextService.EventType.END
                            && "chat.llm.http".equals(event.operation())) {
                        traceEnded.countDown();
                    }
                });
    }

    private TraceContextService.TraceEvent llmHttpEnd(
            List<TraceContextService.TraceEvent> events) {
        return events.stream()
                .filter(event -> event.type()
                        == TraceContextService.EventType.END)
                .filter(event -> "chat.llm.http".equals(event.operation()))
                .findFirst()
                .orElseThrow();
    }

    private record ClientFixture(OpenAiCompatibleChatClient client,
                                 AgentModelSettingsService settingsService) {
    }

    private static final class RecordingFuture<T>
            extends CompletableFuture<T> {
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private final AtomicBoolean mayInterrupt = new AtomicBoolean();
        private final CountDownLatch cancelled = new CountDownLatch(1);

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalls.incrementAndGet();
            mayInterrupt.set(mayInterruptIfRunning);
            cancelled.countDown();
            return super.cancel(mayInterruptIfRunning);
        }
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }
}
