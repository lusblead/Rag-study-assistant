package com.rag.backend.observability.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Enumeration;

/** 为摄取与聊天 HTTP 入口建立彼此独立的 Trace root。 */
@Component
public class TraceHttpFilter extends OncePerRequestFilter {
    public static final String TRACEPARENT_HEADER = "traceparent";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private final TraceContextService traces;

    public TraceHttpFilter(TraceContextService traces) {
        this.traces = traces;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = applicationPath(request);
        boolean post = "POST".equalsIgnoreCase(request.getMethod());
        boolean chat = post && ("/api/agent/chat".equals(path)
                || "/api/agent/chat/stream".equals(path));
        boolean ingest = post && path.endsWith("/ingest")
                && (path.startsWith("/api/documents/")
                || path.startsWith("/api/agent/documents/"));
        return !chat && !ingest;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String path = applicationPath(request);
        String operation = path.endsWith("/ingest")
                ? "ingestion.http"
                : "chat.http";
        boolean asynchronousSse = "/api/agent/chat/stream".equals(path);
        TraceCarrier incoming = new TraceCarrier(
                singleHeader(request, TRACEPARENT_HEADER),
                null);
        // 摄取与聊天必须各自成为独立业务 Trace。外部 traceparent 只作为 link，
        // 不能把一次聊天请求错误接到摄取父链上。通用公网入口也不信任外部
        // X-Correlation-Id：摄取要等持久 Job 建立后才固定使用 jobId；聊天若
        // 未来需要跨业务关联，必须由已认证的业务层显式注入。
        try (TraceSpan span = traces.startRoot(
                operation, incoming.correlationId())) {
            traces.link(incoming, "http.upstream");
            response.setHeader(TRACEPARENT_HEADER, span.carrier().traceparent());
            response.setHeader(TRACE_ID_HEADER, span.traceId());
            if (span.correlationId() != null) {
                response.setHeader(CORRELATION_ID_HEADER, span.correlationId());
            }
            try {
                filterChain.doFilter(request, response);
                if (response.getStatus() >= 500) {
                    span.result("server_error");
                } else if (response.getStatus() >= 400) {
                    span.result("client_error");
                } else {
                    // SSE 的 HTTP scope 只证明 Publisher 已交给 Servlet，不能
                    // 冒充后续 LLM、投递或客户端消费已经成功。
                    span.result(asynchronousSse
                            ? "dispatched"
                            : "success");
                }
            } catch (ServletException | IOException | RuntimeException | Error error) {
                span.error(error);
                throw error;
            }
        }
    }

    private String applicationPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty()
                && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    /** 重复 traceparent 语义不明确，安全降级为新 root，而不是任取一个。 */
    private String singleHeader(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) {
            return null;
        }
        String first = values.nextElement();
        return values.hasMoreElements() ? null : first;
    }
}
