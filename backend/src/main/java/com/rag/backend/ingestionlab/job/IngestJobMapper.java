// 唯一键负责去重，owner/stateVersion/期限条件负责并发提交权。
package com.rag.backend.ingestionlab.job;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
// IngestJobMapper：数据访问契约，关键写入依赖唯一键或条件更新。
public interface IngestJobMapper {
    /** Waiting for protected readers is not a failed deletion attempt. */
    @Update("""
        UPDATE ingest_jobs SET state='RETRY_WAIT', lease_owner=NULL, lease_until=NULL,
          attempt=CASE WHEN attempt>0 THEN attempt-1 ELSE 0 END,
          error_code='MATERIAL_READERS_ACTIVE', next_run_at=#{nextRunAt}, state_version=state_version+1
        WHERE job_id=#{jobId} AND job_type='DELETE' AND state='RUNNING'
          AND lease_owner=#{owner} AND lease_until>=#{now} AND state_version=#{expectedVersion}
        """)
    int deferDeleteForReaders(@Param("jobId") String jobId, @Param("owner") String owner,
            @Param("expectedVersion") long expectedVersion, @Param("now") LocalDateTime now,
            @Param("nextRunAt") LocalDateTime nextRunAt);

    // 创建任务时固定为 QUEUED；同一 version+jobType 的唯一键裁决并发重复创建。
    @Insert("""
        INSERT INTO ingest_jobs
        (job_id, document_id, document_version_id, job_type, state, attempt, max_attempts,
         next_run_at, state_version, submit_traceparent)
        VALUES
        (#{jobId}, #{documentId}, #{documentVersionId}, #{jobType}, 'QUEUED', 0,
         #{maxAttempts}, #{nextRunAt}, 0, #{submitTraceparent})
        """)
    int insert(IngestJob job);

    // 读取当前持久化快照；claim 会携带这里的 stateVersion 做条件更新。
    @Select("SELECT * FROM ingest_jobs WHERE job_id = #{jobId}")
    IngestJob selectById(String jobId);

    // Submitter 复用既有 Version 时必须同时返回它的逻辑 Job，不能返回空 jobId。
    @Select("""
        SELECT * FROM ingest_jobs
         WHERE document_version_id = #{versionId}
           AND job_type = #{jobType}
        """)
    IngestJob selectByVersionAndType(@Param("versionId") long versionId,
                                     @Param("jobType") String jobType);

    @Select("""
        SELECT * FROM ingest_jobs
         WHERE document_id = #{documentId}
           AND job_type = #{jobType}
         ORDER BY created_at DESC
         LIMIT 1
        """)
    IngestJob selectLatestByDocumentAndType(
            @Param("documentId") long documentId,
            @Param("jobType") String jobType);

    // 领取任务只有影响 1 行才成功：状态可领取、退避已到、未超重试且版本仍匹配。
    @Update("""
        UPDATE ingest_jobs
           SET state = 'RUNNING',
               lease_owner = #{owner},
               lease_until = #{leaseUntil},
               attempt = attempt + 1,
               started_at = COALESCE(started_at, #{now}),
               state_version = state_version + 1
         WHERE job_id = #{jobId}
           AND state_version = #{expectedVersion}
           AND next_run_at <= #{now}
           AND attempt < max_attempts
           AND (
                state IN ('QUEUED', 'RETRY_WAIT')
                OR (state = 'RUNNING' AND lease_until < #{now})
           )
        """)
    int tryClaim(@Param("jobId") String jobId,
                 @Param("owner") String owner,
                 @Param("now") LocalDateTime now,
                 @Param("leaseUntil") LocalDateTime leaseUntil,
                 @Param("expectedVersion") long expectedVersion);

    // 续租同时校验 RUNNING、owner、未过期和 stateVersion；旧 owner 无法延长别人的租约。
    @Update("""
        UPDATE ingest_jobs
           SET lease_until = #{leaseUntil},
               state_version = state_version + 1
         WHERE job_id = #{jobId}
           AND state = 'RUNNING'
           AND lease_owner = #{owner}
           AND lease_until >= #{now}
           AND state_version = #{expectedVersion}
        """)
    int renew(@Param("jobId") String jobId,
              @Param("owner") String owner,
              @Param("now") LocalDateTime now,
              @Param("leaseUntil") LocalDateTime leaseUntil,
              @Param("expectedVersion") long expectedVersion);

    // 完成、失败或进入 RETRY_WAIT 都必须由当前 owner 提交；成功后清空租约并推进版本。
    @Update("""
        UPDATE ingest_jobs
           SET state = #{targetState}, lease_owner = NULL, lease_until = NULL,
               error_code = #{errorCode}, error_detail_digest = #{detail},
               next_run_at = #{nextRunAt},
               finished_at = CASE WHEN #{targetState} IN ('SUCCEEDED','FAILED')
                                  THEN #{now} ELSE finished_at END,
               state_version = state_version + 1
         WHERE job_id = #{jobId}
           AND state = 'RUNNING'
           AND lease_owner = #{owner}
           AND lease_until >= #{now}
           AND state_version = #{expectedVersion}
        """)
    int finishOwned(@Param("jobId") String jobId,
                    @Param("owner") String owner,
                    @Param("expectedVersion") long expectedVersion,
                    @Param("targetState") String targetState,
                    @Param("errorCode") String errorCode,
                    @Param("detail") String detail,
                    @Param("nextRunAt") LocalDateTime nextRunAt,
                    @Param("now") LocalDateTime now);

    /**
     * 收口已经耗尽次数、却因为进程退出仍停在可等待状态的 Job。
     * Poller 或 Reconciler 周期调用，并把影响行数记录为指标。
     */
    @Update("""
    UPDATE ingest_jobs
       SET state = 'FAILED',
           error_code = 'RETRY_EXHAUSTED',
           finished_at = #{now},
           lease_owner = NULL,
           lease_until = NULL,
           state_version = state_version + 1
     WHERE attempt >= max_attempts
       AND (
            state IN ('QUEUED', 'RETRY_WAIT')
            OR (state = 'RUNNING' AND lease_until < #{now})
       )
    """)
    int failExhausted(@Param("now") LocalDateTime now);

    /**
     * 扫描到期任务，作为 Outbox 兜底。
     * 查询已到退避时间的 QUEUED/RETRY_WAIT，以及租约过期的 RUNNING。
     */
    @Select("""
        SELECT job_id
          FROM ingest_jobs
         WHERE next_run_at <= #{now}
           AND attempt < max_attempts
           AND (
                state IN ('QUEUED', 'RETRY_WAIT')
                OR (state = 'RUNNING' AND lease_until < #{now})
           )
         ORDER BY next_run_at, job_id
         LIMIT #{limit}
        """)
    List<String> findDueJobIds(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit);
}
