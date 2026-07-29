// 让业务状态与事件原子落库，并为步骤建立幂等记录。
package com.rag.backend.ingestionlab.outbox;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
// OutboxEventMapper：数据访问契约，关键写入依赖唯一键或条件更新。
public interface OutboxEventMapper {
    // 与 Version、Job 同事务插入 NEW 事件；事务提交后 Dispatcher 才能看到它。
    @Insert("""
        INSERT INTO outbox_events
        (event_id, aggregate_type, aggregate_id, event_type,
         payload_json, status, attempts, available_at, state_version)
        VALUES
        (#{eventId}, #{aggregateType}, #{aggregateId}, #{eventType},
         #{payloadJson}, 'NEW', 0, #{availableAt}, 0)
        """)
    int insert(OutboxEvent event);

    // 锁定到期事件并用 SKIP LOCKED 分摊多个 Dispatcher；持锁期间不调用远端 Worker。
    @Select("""
        SELECT * FROM outbox_events
         WHERE (status = 'NEW' OR (status = 'CLAIMED' AND claim_until < #{now}))
           AND available_at <= #{now}
         ORDER BY created_at
         LIMIT #{limit}
         FOR UPDATE SKIP LOCKED
        """)
    List<OutboxEvent> lockBatch(@Param("now") LocalDateTime now,
                                @Param("limit") int limit);

    // 根据读取时的 stateVersion 领取事件；影响 0 行表示其他 Dispatcher 已抢先处理。
    @Update("""
        UPDATE outbox_events
           SET status='CLAIMED', claimed_by=#{owner}, claim_until=#{until},
               attempts=attempts+1, state_version=state_version+1
         WHERE event_id=#{eventId} AND state_version=#{expectedVersion}
        """)
    int claim(@Param("eventId") String eventId, @Param("owner") String owner,
              @Param("until") LocalDateTime until,
              @Param("expectedVersion") long expectedVersion);

    // 只有当前 claimed_by 才能确认发布完成；重复派发由下游 jobId 幂等吸收。
    @Update("""
        UPDATE outbox_events
           SET status='PUBLISHED', published_at=#{now}, claimed_by=NULL,
               claim_until=NULL, state_version=state_version+1
         WHERE event_id=#{eventId} AND status='CLAIMED' AND claimed_by=#{owner}
        """)
    int markPublished(@Param("eventId") String eventId,
                      @Param("owner") String owner,
                      @Param("now") LocalDateTime now);
}