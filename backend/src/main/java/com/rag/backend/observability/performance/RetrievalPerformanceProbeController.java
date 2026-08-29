package com.rag.backend.observability.performance;

import com.rag.backend.agent.retrieval.KnowledgeRetriever;
import com.rag.backend.agent.retrieval.RetrievalExecutionResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * 仅供回环地址性能剖析的检索探针。Bean 默认不存在；即使显式启用，
 * token 为空、缺失或不匹配时也会 fail closed。
 */
@RestController
@ConditionalOnProperty(
        prefix = "rag.performance.probe",
        name = "enabled",
        havingValue = "true")
public final class RetrievalPerformanceProbeController {
    public static final String PATH = "/internal/performance/retrieval";
    public static final String TOKEN_HEADER = "X-Performance-Token";
    public static final int MAX_QUERY_LENGTH = 2048;
    public static final int MAX_TOP_K = 100;

    private final KnowledgeRetriever retriever;
    private final byte[] configuredToken;
    private final boolean tokenConfigured;

    public RetrievalPerformanceProbeController(
            KnowledgeRetriever retriever,
            @Value("${rag.performance.probe.token:}") String token) {
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        String configured = token == null ? "" : token;
        this.configuredToken = configured.getBytes(StandardCharsets.UTF_8);
        this.tokenConfigured = !configured.isBlank();
    }

    @PostMapping(PATH)
    public ResponseEntity<?> retrieve(
            HttpServletRequest servletRequest,
            @RequestHeader(
                    name = TOKEN_HEADER,
                    required = false) String suppliedToken,
            @RequestBody(required = false)
            RetrievalPerformanceProbeRequest request) {
        if (!isLoopback(servletRequest)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ProbeStatus("loopback_required"));
        }
        if (!authorized(suppliedToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ProbeStatus("unauthorized"));
        }
        ValidatedRequest validated = validate(request);
        if (validated == null) {
            return ResponseEntity.badRequest()
                    .body(new ProbeStatus("invalid_request"));
        }
        try {
            RetrievalExecutionResult execution = retriever.retrieveWithResult(
                    validated.courseId(),
                    validated.query(),
                    validated.topK());
            return ResponseEntity.ok(
                    RetrievalPerformanceProbeResponse.from(execution));
        } catch (RuntimeException retrievalFailure) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ProbeStatus("retrieval_failed"));
        }
    }

    private boolean authorized(String suppliedToken) {
        byte[] supplied = (suppliedToken == null ? "" : suppliedToken)
                .getBytes(StandardCharsets.UTF_8);
        boolean equal = MessageDigest.isEqual(configuredToken, supplied);
        return tokenConfigured && equal;
    }

    /**
     * 只信任 Servlet 连接来源，不信任可伪造的 Forwarded/X-Forwarded-For。
     * IPv4 的整个 127/8 与 IPv6 唯一 loopback 地址均允许，其余格式 fail closed。
     */
    private static boolean isLoopback(HttpServletRequest request) {
        if (request == null || request.getRemoteAddr() == null) {
            return false;
        }
        String remoteAddress = request.getRemoteAddr();
        if (remoteAddress.indexOf(':') >= 0) {
            boolean literalCharacters = remoteAddress.chars().allMatch(
                    character -> character == ':'
                            || character == '.'
                            || Character.digit(character, 16) >= 0);
            if (!literalCharacters) {
                return false;
            }
            try {
                InetAddress parsed = InetAddress.getByName(remoteAddress);
                return parsed.isLoopbackAddress();
            } catch (UnknownHostException invalidAddress) {
                return false;
            }
        }
        String[] octets = remoteAddress.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        int[] parsed = new int[4];
        for (int index = 0; index < octets.length; index++) {
            String octet = octets[index];
            if (octet.isEmpty()
                    || !octet.chars().allMatch(Character::isDigit)) {
                return false;
            }
            try {
                parsed[index] = Integer.parseInt(octet);
            } catch (NumberFormatException invalidAddress) {
                return false;
            }
            if (parsed[index] > 255) {
                return false;
            }
        }
        return parsed[0] == 127;
    }

    private static ValidatedRequest validate(
            RetrievalPerformanceProbeRequest request) {
        if (request == null
                || request.courseId() == null
                || request.courseId() <= 0L
                || request.query() == null
                || request.query().length() > MAX_QUERY_LENGTH
                || request.query().isBlank()
                || request.topK() == null
                || request.topK() <= 0
                || request.topK() > MAX_TOP_K) {
            return null;
        }
        return new ValidatedRequest(
                request.courseId(),
                request.query().trim(),
                request.topK());
    }

    public record ProbeStatus(String status) {
        public ProbeStatus {
            Objects.requireNonNull(status, "status");
        }
    }

    private record ValidatedRequest(
            long courseId,
            String query,
            int topK) {
    }
}
