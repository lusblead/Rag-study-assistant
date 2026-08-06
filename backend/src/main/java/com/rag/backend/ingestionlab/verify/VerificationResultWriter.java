package com.rag.backend.ingestionlab.verify;

import com.rag.backend.ingestionlab.outbox.DocumentVersionMapper;
import com.rag.backend.ingestionlab.outbox.DocumentVersionRow;
import com.rag.backend.ingestionlab.state.DocumentVersionState;
import com.rag.backend.ingestionlab.state.DocumentVersionStateMachine;
import com.rag.backend.ingestionlab.state.VersionTransitionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 只负责把已计算好的验证报告写回 MySQL。
 * 独立 Bean 使 Spring 事务代理能够真正拦截公开方法。
 */
@Service
public class VerificationResultWriter {
    private final DocumentVersionMapper mapper;

    public VerificationResultWriter(DocumentVersionMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public void write(
            DocumentVersionRow version,
            DocumentVersionState target,
            IndexVerifier.VerificationReport report,
            String reportJson) {
        DocumentVersionStateMachine.requireAllowed(
                DocumentVersionState.VERIFYING, target);
        int changed = mapper.completeVerification(
                version.getId(),
                version.getStateVersion(),
                target.name(),
                report.digest(),
                reportJson);
        if (changed != 1) {
            throw new VersionTransitionService
                    .ConcurrentVersionChangeException(version.getId());
        }
    }
}