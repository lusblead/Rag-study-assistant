package com.rag.backend.ingestionlab.state;

import com.rag.backend.ingestionlab.outbox.DocumentVersionMapper;
import com.rag.backend.ingestionlab.outbox.DocumentVersionRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把纯状态机与数据库 CAS 连接起来。
 *
 * 状态机负责回答“业务上能不能这样跳”；
 * Mapper 的 stateVersion 条件负责回答“当前 Worker 还有没有提交权”。
 */
@Service
public class VersionTransitionService {
    private final DocumentVersionMapper mapper;

    public VersionTransitionService(DocumentVersionMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public DocumentVersionRow transition(
            long versionId,
            DocumentVersionState targetState) {
        DocumentVersionRow current = mapper.findById(versionId);
        if (current == null) {
            throw new IllegalArgumentException(
                    "Unknown document version: " + versionId);
        }

        DocumentVersionState currentState =
                DocumentVersionState.valueOf(current.getState());
        DocumentVersionStateMachine.requireAllowed(currentState, targetState);

        int changed = mapper.transition(
                versionId,
                currentState.name(),
                targetState.name(),
                current.getStateVersion());
        if (changed != 1) {
            // 影响 0 行通常表示另一个 Worker 已经推进状态；
            // 不能忽略后继续执行远端副作用。
            throw new ConcurrentVersionChangeException(versionId);
        }

        // 返回本次成功 CAS 后的内存快照，供后续步骤携带新的 stateVersion。
        current.setState(targetState.name());
        current.setStateVersion(current.getStateVersion() + 1);
        return current;
    }

    public static final class ConcurrentVersionChangeException
            extends RuntimeException {
        public ConcurrentVersionChangeException(long versionId) {
            super("Document version changed concurrently: " + versionId);
        }
    }
}