// MyBatis 实现为新 Issue 生成物理 UUID，并把稳定 issueKey 的去重交给数据库唯一约束。
package com.rag.backend.ingestionlab.reconcile;

import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
// MyBatisIssueRepository 把领域 Issue 转给 Mapper；它不判断差异，也不决定修复动作。
public class MyBatisIssueRepository implements IssueRepository {
    // Mapper 承担 Issue upsert 和按完成扫描范围关闭，Repository 不绕过这两个生命周期入口。
    private final ReconciliationIssueMapper mapper;

    // 构造器只连接 Repository 与 MyBatis Mapper，runId 和领域 Issue 仍由 Reconciler 提供。
    public MyBatisIssueRepository(ReconciliationIssueMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    // upsert 本轮问题，完整扫描后再关闭消失问题。
    public void observe(String runId, ConsistencyIssue issue) {
        mapper.observe(UUID.randomUUID().toString(), runId, issue);
    }

    @Override
    // upsert 本轮问题，完整扫描后再关闭消失问题。
    public void resolveVectorInventoryNotSeen(long versionId, String runId) {
        mapper.resolveVectorInventoryNotSeen(versionId, runId);
    }
}