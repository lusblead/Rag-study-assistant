---
feature_id: "rag.session-materials"
title: "会话资料版本保持与安全回收"
status: "implemented"
---

# 会话资料版本保持与安全回收

学习连续性优先。首次问答由服务端在短事务内锁会话及按 ID 排序的文档行，
保存文档到 documentVersionId 的清单；后续问答和补查不再选择最新版。
客户端只能提交 sessionId/courseId，不能上传版本清单。现有项目没有多用户鉴权体系，
此处沿用课程归属检查，不声称新增了用户级访问控制。

会话默认不活跃保留 7 天；合法读取在到期前续期，已到期不可自行续活。版本默认至少保留
7 天。两者均为首版可配置运维默认值，不代表业务已经校准了最优期限。
恢复页面只查询状态，不续期。每轮与恢复时检查更新；一个固定提示区展示状态。
旧会话保留原版本，用户通过“使用当前可用资料开启新对话”切换，旧历史不带入新会话。
旧聊天若没有可证实的版本绑定，则要求新建，不能替旧回答补造版本身份。

## Runtime flow

```mermaid
flowchart TD
    E1(["E1 问答请求"]) --> A1["A1 锁会话与文档；读取或建立绑定"]
    A1 --> D1{"D1 归属、保留期和资料完整性有效"}
    D1 -->|否| X1(["X1 明确失败；不保存成功历史；若在生成前则不调用模型"])
    D1 -->|是| T1["T1 保存绑定并登记持久读者"]
    T1 --> A2["A2 固定范围向量、词法检索和正文取数"]
    A2 --> D2{"D2 取数正常且范围仍有效"}
    D2 -->|否| C1["C1 finally 释放读者；传播失败"]
    D2 -->|是| T4["T4 显式释放取数保护"]
    T4 --> A3["A3 生成后复核并原子保存消息与版本引用"]
    A3 --> D5{"D5 最终资格及消息事务成功"}
    D5 -->|否| X1
    D5 -->|是| X2(["X2 输出原版答案及资料状态"])
    C1 --> X1
    E2(["E2 普通旧版回收周期"]) --> D3{"D3 最短保留已到且无有效会话保留"}
    D3 -->|否| X3(["X3 保留数据并等待"])
    D3 -->|是| T2["T2 关闭新读取入口"]
    T2 --> D4{"D4 在途读者全部释放"}
    E4(["E4 文档 DELETE Job；前提是已提交 DELETING 墓碑"]) --> D4
    D4 -->|否| X3
    D4 -->|是| A4["A4 幂等删除向量、制品与正文"]
    A4 --> D6{"D6 外部清理成功"}
    D6 -->|否| C2["C2 保留关闭入口状态，记录失败待重放"]
    C2 --> X4
    D6 -->|是| D7{"D7 当前回收路径"}
    D7 -->|普通旧版回收| T3["T3 提交版本 read_status 为 DELETED"]
    D7 -->|文档 DELETE Job| T6["T6 文档生命周期 DELETING 变 DELETED"]
    T6 --> X4
    T3 --> X4(["X4 完成或下个周期重试"])
    E3(["E3 恢复会话或展开引用"]) --> A5["A5 检查会话状态并读取准确版本"]
    A5 --> X2
    G1[["G1 申请与关闭入口使用同一文档锁"]] -.-> T1
    G1 -.-> T2
    G2[["G2 失效不能被降级或作为迟到答案提交"]] -.-> D2
    G2 -.-> A3
```

## Component sequence

```mermaid
sequenceDiagram
    participant UI
    participant Chat
    participant Materials
    participant DB
    participant Retrieval
    participant GC
    UI->>Chat: sessionId、courseId、问题
    Chat->>Materials: acquire
    Materials->>DB: READ_COMMITTED 短事务锁会话与文档；绑定/续期/读者登记
    DB-->>Materials: commit
    Chat->>Retrieval: retrieveInScope
    GC->>DB: 同一文档锁；检查保留并关闭入口
    alt 仍有保留会话或在途读者
        DB-->>GC: 等待；不删除
    else 可以回收
        DB-->>GC: commit RETIRING
        GC->>Retrieval: 删除指定版本向量及制品
        GC->>DB: 删除正文并提交 DELETED
    end
    Retrieval-->>Chat: 物化证据或异常
    Chat->>Materials: 复核；finally 显式释放读者
    Chat->>Chat: 生成完整候选（SSE 也缓冲）
    Chat->>Materials: complete
    Materials->>DB: 锁会话/文档；复核资格、原子保存消息对和 evidence_json
    alt 仍有效
        DB-->>UI: 答案与原版引用
    else 撤回、过期或证据不完整
        Materials-->>UI: MATERIAL_SCOPE_UNAVAILABLE；无成功历史
    end
```

## State lifecycle

```mermaid
stateDiagram-v2
    UNBOUND --> BOUND: T1 首次合法检索保存清单
    READABLE --> RETIRING: T2 无会话保留且符合最短保留期
    RETIRING --> DELETED: T3 在途读者清零且清理成功
    READING --> RELEASED: T4 实际取数退出后显式删除读者记录
    DOCUMENT_DELETING --> DOCUMENT_DELETED: T6 DELETE Job 清理完成
```

## Safeguards and failure behavior

- 新清单只选文档指针与 ACTIVE 版本一致的记录；旧清单允许仍可读的 SUPERSEDED，
  但不能把所有 SUPERSEDED 开放给任意请求。空清单同样冻结，后来新增资料只提示更新。
- 激活、撤回、获取读取资格和回收决策均锁同一个 documents 行；锁不跨 Milvus/LLM I/O。
  GC 先提交 RETIRING 再物理删除，已登记读者未释放时只等待。过期会话不能重新注册。
- material_readers 没有租约超时，也不会随删除会话级联删除。进程故障后自动回收会保守阻塞；
  需人工确认对应 process_owner 的进程及后续取数已经终止，才能释放。不得凭记录年龄判断。
- 正文明显缺失、数量与激活期预期不符、串版均使用 EVIDENCE_MISSING；撤回、回收和到期使用
  独立原因枚举。双源收集器不能吞掉 MaterialScopeException。临时单路异常可在原范围降级；
  普通无命中保持无证据语义。
- 模型生成后再次复核。同步和 SSE 成功消息对及 evidence_json 在同一事务提交；SSE 完整
  缓冲，提交前失效、上游失败或取消无答案 delta/成功历史。接受结果的时点是提交事务，
  之后的网络断开不会回滚已保存历史，后续撤回也不追溯抹除历史。
- 引用展示先读会话状态，再以原 documentVersionId 与 chunkId 取数；失效会明确显示原因，
  不打开当前文档的新版本。历史 JSON 保留原始版本元数据。
- 文档撤回经已有 DeleteRequestService 墓碑、撤销摄取 Lease、Job/Outbox，再由 DeleteSaga
  清理。读者未退出时 DELETE Job 延后 30 秒且不消耗失败重试额度。课程删除先为各文档排队，
  返回 409 等待；只有文档物理清理完才允许课程元数据删除。

## Recovery and observability

回收默认每 60 秒处理最多 50 个可回收候选；每个版本每轮一个尝试。Milvus 复用原有 RPC
deadline（默认 30 秒），失败保留 RETIRING，下轮幂等重放。候选查询排除仍保留/有读者版本，
避免前 50 个长期保留对象饿死后续回收。日志记录版本 ID、失败类型与进程 owner/PID/启动时间。
SQL 日志改走 SLF4J，默认 INFO 不输出新增 evidence_json 参数；不要在真实资料环境开启 SQL DEBUG。
配置、崩溃读者的人工恢复步骤、研究来源、实际测试及未执行边界见
[交付说明](../../../backend/docs/session-material-lifecycle.md)。

入口、决策、状态、保障和具名测试映射位于 traceability.yaml。这里是组件级保留与回收协议，
不宣称跨 MySQL/Milvus 事务、分布式租约、模型质量提升或生产环境验收。
