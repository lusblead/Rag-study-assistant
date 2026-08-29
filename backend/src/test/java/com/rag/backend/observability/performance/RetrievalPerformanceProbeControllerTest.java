package com.rag.backend.observability.performance;

import com.rag.backend.agent.rerank.RerankExecutionResult;
import com.rag.backend.agent.retrieval.CandidateSourceType;
import com.rag.backend.agent.retrieval.KnowledgeRetriever;
import com.rag.backend.agent.retrieval.RetrievalDiagnostics;
import com.rag.backend.agent.retrieval.RetrievalExecutionResult;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RetrievalPerformanceProbeControllerTest {
    private static final String TOKEN = "local-performance-token";

    private KnowledgeRetriever retriever;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        retriever = mock(KnowledgeRetriever.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new RetrievalPerformanceProbeController(
                                retriever, TOKEN))
                .build();
    }

    @Test
    void missingOrWrongTokenReturnsUnauthorizedWithoutRetrieval()
            throws Exception {
        String request = """
                {"courseId":71,"query":"safe query","topK":5}
                """;

        mockMvc.perform(post(RetrievalPerformanceProbeController.PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is("unauthorized")));
        mockMvc.perform(post(RetrievalPerformanceProbeController.PATH)
                        .header(
                                RetrievalPerformanceProbeController.TOKEN_HEADER,
                                "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is("unauthorized")));

        verifyNoInteractions(retriever);
    }

    @Test
    void emptyConfiguredTokenFailsClosedWithoutRetrieval()
            throws Exception {
        MockMvc emptyTokenMvc = MockMvcBuilders.standaloneSetup(
                        new RetrievalPerformanceProbeController(
                                retriever, "   "))
                .build();

        emptyTokenMvc.perform(post(RetrievalPerformanceProbeController.PATH)
                        .header(
                                RetrievalPerformanceProbeController.TOKEN_HEADER,
                                "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"courseId":71,"query":"safe query","topK":5}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is("unauthorized")));

        verifyNoInteractions(retriever);
    }

    @Test
    void nonLoopbackSourceIsForbiddenEvenWithSpoofedForwardingHeader()
            throws Exception {
        mockMvc.perform(post(RetrievalPerformanceProbeController.PATH)
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.71");
                            return request;
                        })
                        .header(
                                "X-Forwarded-For",
                                "127.0.0.1")
                        .header(
                                RetrievalPerformanceProbeController.TOKEN_HEADER,
                                TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"courseId":71,"query":"safe query","topK":5}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath(
                        "$.status", is("loopback_required")));

        verifyNoInteractions(retriever);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1",
            "127.71.0.9",
            "::1",
            "0:0:0:0:0:0:0:1",
            "::ffff:127.0.0.1",
            "0:0:0:0:0:ffff:7f00:1"
    })
    void ipv4AndIpv6LoopbackSourcesAreAccepted(String remoteAddress)
            throws Exception {
        when(retriever.retrieveWithResult(
                71L, "safe query", 5))
                .thenReturn(RetrievalExecutionResult.unobserved(List.of()));

        mockMvc.perform(post(RetrievalPerformanceProbeController.PATH)
                        .with(request -> {
                            request.setRemoteAddr(remoteAddress);
                            return request;
                        })
                        .header(
                                RetrievalPerformanceProbeController.TOKEN_HEADER,
                                TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"courseId":71,"query":"safe query","topK":5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("empty")));

        verify(retriever).retrieveWithResult(71L, "safe query", 5);
    }

    @Test
    void authorizedProbeReturnsOnlySanitizedAggregates()
            throws Exception {
        String query = "sensitive-query-canary";
        long courseId = 4_242_424_242L;
        RetrievedChunk privateChunk = new RetrievedChunk(
                9_191_919_191L,
                8_282_828_282L,
                "secret-document-canary",
                "secret-title-canary",
                "private-chunk-body-canary",
                1,
                0.9);
        RetrievalDiagnostics diagnostics = new RetrievalDiagnostics(
                false,
                RetrievalDiagnostics.EmptyReason.NONE,
                List.of(
                        new RetrievalDiagnostics.Source(
                                CandidateSourceType.DENSE,
                                true,
                                null,
                                4,
                                11L),
                        new RetrievalDiagnostics.Source(
                                CandidateSourceType.LEXICAL,
                                true,
                                null,
                                3,
                                12L)),
                new RetrievalDiagnostics.Rerank(
                        RerankExecutionResult.Mode.LOCAL,
                        RerankExecutionResult.Mode.LOCAL,
                        RerankExecutionResult.FallbackReason.NONE,
                        RerankExecutionResult.FailureType.NONE,
                        RerankExecutionResult.SemanticEmptyReason.NONE,
                        null,
                        false,
                        null,
                        7,
                        1,
                        13L),
                new RetrievalDiagnostics.Diversity(
                        false,
                        "disabled",
                        null,
                        1,
                        1,
                        0.0,
                        0.0,
                        1,
                        1,
                        14L));
        when(retriever.retrieveWithResult(courseId, query, 5))
                .thenReturn(new RetrievalExecutionResult(
                        List.of(privateChunk), diagnostics));

        String response = mockMvc.perform(
                        post(RetrievalPerformanceProbeController.PATH)
                                .header(
                                        RetrievalPerformanceProbeController
                                                .TOKEN_HEADER,
                                        TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "courseId": 4242424242,
                                          "query": "  sensitive-query-canary  ",
                                          "topK": 5
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("success")))
                .andExpect(jsonPath("$.degraded", is(false)))
                .andExpect(jsonPath("$.emptyReason", is("none")))
                .andExpect(jsonPath("$.sourceCandidateCount", is(7)))
                .andExpect(jsonPath("$.rerankInputCount", is(7)))
                .andExpect(jsonPath("$.resultCount", is(1)))
                .andExpect(jsonPath("$.stages[0].stage", is("source.dense")))
                .andExpect(jsonPath("$.stages[0].latencyNanos", is(11)))
                .andExpect(jsonPath("$.stages[1].stage", is("source.lexical")))
                .andExpect(jsonPath("$.stages[1].latencyNanos", is(12)))
                .andExpect(jsonPath("$.stages[2].stage", is("rerank")))
                .andExpect(jsonPath("$.stages[2].latencyNanos", is(13)))
                .andExpect(jsonPath("$.stages[3].stage", is("diversity")))
                .andExpect(jsonPath("$.stages[3].latencyNanos", is(14)))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        verify(retriever).retrieveWithResult(courseId, query, 5);
        for (String canary : List.of(
                query,
                Long.toString(courseId),
                "9191919191",
                "8282828282",
                "secret-document-canary",
                "secret-title-canary",
                "private-chunk-body-canary")) {
            assertFalse(response.contains(canary), canary);
        }
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidBoundsReturnBadRequestWithoutRetrieval(String request)
            throws Exception {
        mockMvc.perform(post(RetrievalPerformanceProbeController.PATH)
                        .header(
                                RetrievalPerformanceProbeController.TOKEN_HEADER,
                                TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is("invalid_request")));

        verifyNoInteractions(retriever);
    }

    @Test
    void retrievalFailureReturnsGenericStatusWithoutLeakingMessage()
            throws Exception {
        when(retriever.retrieveWithResult(
                71L, "safe query", 5))
                .thenThrow(new IllegalStateException(
                        "private-query-or-provider-message"));

        String response = mockMvc.perform(
                        post(RetrievalPerformanceProbeController.PATH)
                                .header(
                                        RetrievalPerformanceProbeController
                                                .TOKEN_HEADER,
                                        TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"courseId":71,"query":"safe query","topK":5}
                                        """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status", is("retrieval_failed")))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertFalse(response.contains("private-query-or-provider-message"));
    }

    private static Stream<String> invalidRequests() {
        return Stream.of(
                """
                {"courseId":0,"query":"safe query","topK":5}
                """,
                """
                {"courseId":71,"query":"   ","topK":5}
                """,
                """
                {"courseId":71,"query":"safe query","topK":0}
                """,
                """
                {"courseId":71,"query":"safe query","topK":101}
                """,
                "{\"courseId\":71,\"query\":\""
                        + "q".repeat(
                                RetrievalPerformanceProbeController
                                        .MAX_QUERY_LENGTH + 1)
                        + "\",\"topK\":5}");
    }
}
