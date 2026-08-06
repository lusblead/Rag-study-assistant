// 指标适配器分别维护扫描 Counter、问题观察 Counter、OPEN Issue Gauge 和卡住 Job Gauge。
package com.rag.backend.ingestionlab.observe;

import com.rag.backend.ingestionlab.reconcile.ReconciliationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
// 低基数指标：只按有限问题类型计数和维护 Gauge。
public class IngestionMetrics implements ReconciliationService.IssueMetrics {
    private final MeterRegistry registry;
    private final Map<String, Counter> issueCounters = new ConcurrentHashMap<>();
    private final Counter cleanScans;
    private final Counter dirtyScans;
    private final AtomicInteger stuckJobs = new AtomicInteger();
    // 当前 OPEN Issue 数量是 Gauge，由定时数据库查询刷新；它与观察事件 Counter 含义不同。
    private final AtomicInteger openIssues = new AtomicInteger();

    // 构造时向同一个 MeterRegistry 注册固定名称和低基数标签，业务 ID 只进入日志。
    public IngestionMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.cleanScans = registry.counter("ingestion.reconciliation.scans", "result", "clean");
        this.dirtyScans = registry.counter("ingestion.reconciliation.scans", "result", "dirty");
        registry.gauge("ingestion.jobs.stuck", stuckJobs);
        registry.gauge("ingestion.reconciliation.issues.open", openIssues);
    }

    @Override
    // 更新低基数 Counter/Gauge，不使用用户 ID 标签。
    public void issueObserved(String type) {
        // 幂等写：键存在就复用，重放不增加逻辑数据。
        issueCounters.computeIfAbsent(type, key -> registry.counter(
                "ingestion.reconciliation.issue.observations", "type", key)).increment();
    }

    @Override
    // 更新低基数 Counter/Gauge，不使用用户 ID 标签。
    public void scanCompleted(boolean clean) {
        (clean ? cleanScans : dirtyScans).increment();
    }

    // 更新低基数 Counter/Gauge，不使用用户 ID 标签。
    public void updateStuckJobs(int count) { stuckJobs.set(count); }

    // 由定时采集器执行 SELECT COUNT(*) WHERE state='OPEN' 后刷新当前值。
    public void updateOpenIssues(int count) { openIssues.set(count); }
}