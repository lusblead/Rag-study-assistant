// Mapper 目的：持久化每个 jobId+stepName 的输入摘要、运行状态和可复用输出。
package com.rag.backend.ingestionlab.step;

import org.apache.ibatis.annotations.*;

@Mapper
// IngestStepMapper：数据访问契约，关键写入依赖唯一键或条件更新。
public interface IngestStepMapper {
    // 用 jobId+stepName 读取步骤快照，判断能否复用 DONE 输出或需要继续执行。
    @Select("SELECT * FROM ingest_steps WHERE job_id=#{jobId} AND step_name=#{stepName}")
    IngestStepRow find(@Param("jobId") String jobId,
                       @Param("stepName") String stepName);

    // 首次执行创建 PENDING；重复派发由主键忽略插入，随后读取同一记录。
    @Insert("""
        INSERT IGNORE INTO ingest_steps
        (job_id, step_name, state, attempt, input_digest, processed_count)
        VALUES (#{jobId}, #{stepName}, 'PENDING', 0, #{inputDigest}, 0)
        """)
    int insertIfAbsent(@Param("jobId") String jobId,
                       @Param("stepName") String stepName,
                       @Param("inputDigest") String inputDigest);

    // 只有 inputDigest 相同的步骤才可重跑；输入变化由 StepExecutor 明确报冲突。
    @Update("""
        UPDATE ingest_steps
           SET state='RUNNING', attempt=attempt+1, started_at=NOW(6), error_code=NULL
         WHERE job_id=#{jobId} AND step_name=#{stepName}
           AND state IN ('PENDING','RUNNING','FAILED')
           AND input_digest=#{inputDigest}
        """)
    int markRunning(@Param("jobId") String jobId,
                    @Param("stepName") String stepName,
                    @Param("inputDigest") String inputDigest);

    // 仅 RUNNING 且摘要仍相同才能固化输出引用、摘要和数量，供重放复用。
    @Update("""
        UPDATE ingest_steps
           SET state='DONE', output_ref=#{outputRef}, output_digest=#{outputDigest},
               processed_count=#{count}, finished_at=NOW(6)
         WHERE job_id=#{jobId} AND step_name=#{stepName}
           AND state='RUNNING' AND input_digest=#{inputDigest}
        """)
    int markDone(@Param("jobId") String jobId, @Param("stepName") String stepName,
                 @Param("inputDigest") String inputDigest,
                 @Param("outputRef") String outputRef,
                 @Param("outputDigest") String outputDigest,
                 @Param("count") int count);
}