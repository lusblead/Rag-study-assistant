package com.rag.backend.rerank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.agent.rerank.RerankExecutionResult;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import com.rag.backend.agent.settings.AgentModelSettingsService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.env.MockEnvironment;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RerankSettingsServiceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRemoteResponses")
    void nonEmptyInputRejectsInvalidRemoteResults(
            String caseName,
            String responseBody) throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = successfulResponse(responseBody);
        returnResponse(httpClient, response);
        RerankSettingsService service = serviceUsing(
                httpClient, Duration.ofSeconds(1));

        RerankExecutionResult.TechnicalFailure failure = assertThrows(
                RerankExecutionResult.TechnicalFailure.class,
                () -> service.rerankRemote(
                        "query", candidates(), 2, remoteSettings(
                                "http://unused.invalid/v1")),
                caseName);

        assertEquals(
                RerankExecutionResult.FailureType.INVALID_RESPONSE,
                failure.failureType());
    }

    private static Stream<Arguments> invalidRemoteResponses() {
        return Stream.of(
                Arguments.of(
                        "empty results",
                        "{\"results\":[]}"),
                Arguments.of(
                        "duplicate indexes",
                        "{\"results\":["
                                + "{\"index\":0,\"relevance_score\":0.9},"
                                + "{\"index\":0,\"relevance_score\":0.8}]}"),
                Arguments.of(
                        "out-of-range index",
                        "{\"results\":["
                                + "{\"index\":0,\"relevance_score\":0.9},"
                                + "{\"index\":2,\"relevance_score\":0.8}]}"),
                Arguments.of(
                        "missing result field",
                        "{\"results\":["
                                + "{\"index\":0,\"relevance_score\":0.9},"
                                + "{\"index\":1}]}"),
                Arguments.of(
                        "non-finite score",
                        "{\"results\":["
                                + "{\"index\":0,\"relevance_score\":1e309},"
                                + "{\"index\":1,\"relevance_score\":0.8}]}"));
    }

    private static HttpResponse<String> successfulResponse(String body) {
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        return response;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void returnResponse(
            HttpClient httpClient,
            HttpResponse<String> response) throws Exception {
        doReturn(response).when(httpClient).send(
                any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class));
    }

    static RerankSettingsService serviceUsing(
            HttpClient httpClient,
            Duration requestTimeout) {
        return new RerankSettingsService(
                mock(RerankSettingsMapper.class),
                new MockEnvironment(),
                new ObjectMapper(),
                mock(AgentModelSettingsService.class),
                httpClient,
                requestTimeout);
    }

    static RerankSettings remoteSettings(String baseUrl) {
        RerankSettings settings = new RerankSettings();
        settings.setProvider("siliconflow");
        settings.setBaseUrl(baseUrl);
        settings.setModel("test-model");
        settings.setApiKey("local-test-key");
        settings.setFailOpen(false);
        return settings;
    }

    static List<RetrievedChunk> candidates() {
        return List.of(
                new RetrievedChunk(
                        1L, 11L, "document-1", "title-1",
                        "first content", 1, 0.8),
                new RetrievedChunk(
                        2L, 12L, "document-2", "title-2",
                        "second content", 1, 0.6));
    }
}
