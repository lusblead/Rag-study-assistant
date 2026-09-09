# 会话资料版本保持与安全回收：实现及验证交付

本轮按照“学习连续性优先”实现会话固定资料、更新/失效提示与安全回收。
这是功能分支的源码及本地组件验证交付，不是生产部署或真实模型质量验收。
未变更向量模型、RRF、重排算法或现有评测参数。

## 三种保证及前后变化

| 保证 | 改动前 | 本轮实现 |
|---|---|---|
| 版本上线资格 | Staging → Verify → Activate，检索选择 ACTIVE | 保持；新会话绑定只选活动指针与 ACTIVE 状态一致、且 READABLE 的版本 |
| 会话版本连续性 | 每次 retrieveWithResult 重新取得活动范围，不能证明整个对话固定 | 首次检索持久保存“documentId → documentVersionId”清单；后续及恢复时复用；SUPERSEDED 不等于不可读 |
| 旧数据读取保护 | 单次固定 ID 不构成保留保证；缺失正文可能被跳过 | 会话保留 + 持久在途读者 + 先关闭入口再回收；明确缺失或失效阻断整轮 |

`documentVersionId` 是版本记录主键，不是展示 `version_no`，也不是并发控制的 `state_version`。
会话固定的是多份文档各自的版本清单，包括合法空清单；不是课程共用一个版本号。
固定版本保证证据来源连续，不保证模型输出相同，也不等于 MySQL–Milvus 分布式事务。

## 完整聊天调用链

1. 前端 `ChatPage.vue` 首次提交前调用 `SessionMaterialController.create`，获得稳定 sessionId；
   之后由 `RagChatController` → `RagChatService.prepare` 读取历史并申请 `SessionMaterialService.acquire`。
2. READ_COMMITTED 短事务先锁 `chat_sessions`，再按 ID 锁课程 `documents` 行；首次绑定、
   保留续期与 `material_readers` 登记一起提交。同会话并发首次请求只建立一份绑定。
   这里不串行化整轮模型生成，也不承诺并发提问按用户发起顺序完成。
3. `retrieveInScope` 将服务端清单传给 Dense / Lexical / 补查 / RRF / Rerank；
   `RetrievedChunk` 与 `CitationSource` 保留 documentVersionId。旧 `retrieveWithResult` 仍服务
   非会话兼容入口，不能把该入口的每次 ACTIVE 选择说成会话版本保持。
4. 会话范围内正文不存在或版本不符，抛出 `MaterialScopeException`；
   `DualCandidateSourceCollector` 原样传播，不把它吞成普通单路降级。
   临时单路故障仍可使用同范围另一来源；普通无命中仍走无证据决策。
5. 物化取数完成后复核，try-with-resources 显式释放读者；不会把数据库行锁一直持有到
   模型生成结束。随后在内存证据上执行原有决策、Prompt、生成及可选 Grounding。
6. `complete` 再锁会话与文档，检查原版资格；`MyBatisChatHistoryService.appendTurn` 在同一事务
   提交 user/assistant 和 `evidence_json`。生成期间撤回、到期或证据缺失均拒绝迟到结果。
7. 同步返回完整答案。生产 SSE 无论 Grounding 是否开启都先缓冲完整候选，最终复核并保存
   后才发送一个完整答案 delta。初始 session/references/metadata 不是成功证明。提交前取消或
   模型流失败不保存成功消息；提交后客户端断开不能回滚已保存历史。
8. 恢复历史读取原 evidence_json；`ReferencesList.vue` 展开时调用受控引用接口，必须匹配
   会话、课程、chunkId 和 documentVersionId，失败显示不可用，不悄悄打开最新版。

前端在恢复会话、每轮 metadata 及问答结束状态查询时检查更新。用固定提示区保留更新通知，
不每轮重复弹窗；本轮没有页面停留期间的即时推送。用户显式新建会话才能采用当前资料，
旧历史不带入新会话。历史消息存在但没有绑定时返回 `LEGACY_SESSION_UNBOUND`，不伪造溯源。

## 两条清理路径

### 普通换代

`MaterialReclamationScheduler.collect` → `candidates` → `reclaim`：旧版先保持
`state=SUPERSEDED, read_status=READABLE`。满足最短保留期且无有效会话保留后，在与取数申请
共用的文档行锁下提交 `read_status=RETIRING`，关闭新入口。读者没有退出则只等待；读者清零
后调用 `ConsistentVectorStore.deleteByVersion`、`ArtifactStore.deletePrefix`，最后删正文并
提交 `read_status=DELETED`。不删除版本元数据和历史绑定，以保留可解释的原版身份。

外部清理失败留在 RETIRING，下次周期幂等重放，不恢复可读状态。候选查询排除有读者或保留
的版本，避免前 50 个长期保留对象占满候选窗口。每周期每版本一次尝试，无忙循环重试。

### 明确撤回 / 删除文档

`DocumentServiceImpl.delete` → `DeleteRequestService.request` → 文档墓碑 DELETING、
撤销摄取 Job 提交权、DELETE Job/Outbox 同事务 → `DeleteJobWorker` → `DeleteSaga` →
`MyBatisDeletePort.loadTombstoned` → `awaitDocumentReaders` → 幂等删向量、制品、正文和源文件
→ 文档 DELETED。撤回可以终止会话保留资格，但仍不能跳过在途读者检查。

新读者申请与墓碑提交锁相同的 documents 行：要么读者已登记、删除等待；要么撤回先提交、
读者被拒绝。等待读者时 Job 延后 30 秒、归还这次 claim 的 attempt，不耗尽真实故障重试额度；
其他外部错误沿用既有有界 Job 重试。等待读者本身可以无限等待，崩溃残留要人工确认处理。

课程删除不再直接绕过上述协议：先为未清理文档排队并返回 409，文档清理完后再次请求才删
课程元数据。该 409 有已提交的排队副作用。失败 Job 不会因再次点删除自动重置为成功。

## 数据、配置和部署边界

Flyway 新迁移：`V6__session_material_versions.sql`，新增：

- `document_versions.read_status`：READABLE / RETIRING / DELETED，独立于摄取 state。
- `chat_material_scopes`：会话保留截止时间；`chat_material_versions`：固定版本清单。
- `material_readers`：read_id、session_id、document_version_id、process_owner、started_at。
  没有自动到期列，也没有随会话删除级联清理的外键。
- `chat_messages.evidence_json`：原始引用与答案元数据，恢复时不替换版本。

| 配置 / 环境变量 | 默认值 | 含义 |
|---|---|---|
| rag.materials.session-retention / RAG_SESSION_RETENTION | P7D | 不活跃保留；合法读取到期前续期，到期后不能复活 |
| rag.materials.version-retention / RAG_VERSION_RETENTION | P7D | SUPERSEDED 最短保留；有有效会话时继续延长 |
| rag.materials.cleanup-enabled / RAG_MATERIAL_CLEANUP_ENABLED | true | 周期回收开关；还受 ingestion.scheduling.enabled 控制 |
| rag.materials.cleanup-interval-ms / RAG_MATERIAL_CLEANUP_INTERVAL_MS | 60000 | 周期间隔，每批最多 50 个候选 |

这两项 7 天是首版可配置默认值，并非从历史业务数据证明的最优值。状态查询不续期，有效引用
展开属于读取，会续期。部署时应核对 MySQL 与应用时间约定，不能混用不一致的时区处理保留期限。

升级前备份数据库；按正常 Flyway 流程迁移，不手工修改历史迁移。V6 是新增列/表，但新旧
应用不能混跑来读写同一资料库：旧代码不遵守读者协议。回滚不得直接删表、清读者或回退到
绕过保护的旧删除代码。应先关闭回收、排空请求和旧 Worker，确认在途取数已结束，再制定
保持数据及绑定信息的前向修复或恢复方案。本轮未执行真实库迁移/回滚演练。

项目没有多用户认证体系，本轮只保持原课程/会话归属校验，不宣称用户级数据隔离。
原有手工构造且未注入 materials 的兼容单测入口仍保留旧语义；生产 Spring 注入是必需的，
新增生命周期测试显式装配 materials，不用旧构造器的绿灯替代新保证。

## 崩溃读者恢复手册

1. 查明阻塞的版本及 `material_readers` 行，只读关联其 read_id、process_owner、started_at。
2. 依据应用启动日志 `Material reader owner registered` 关联 owner、PID、进程启动时间及
   对应部署实例，确认该实例已停止、不会恢复该请求，并确认真实取数已经退出。仅 PID 相同
   不能证明是同一进程；仅记录很旧或会话过期不能证明读取结束。
3. 在隔离受影响实例并确认上一步后，由运维以 read_id + process_owner + versionId 精确
   删除对应残留记录，保留操作审计。不要批量按 started_at 或 sessionId 清理。
4. 再次观察回收/DELETE Job 重试，确认外部删除完成后状态才成为 DELETED。

这是安全优先的保守策略：实例崩溃可能占用存储，不能为了自动释放而虚构租约到期等于读取结束。
日志仅记录 owner、PID、版本 ID、状态及失败类型，不打印引用正文。MyBatis 从 StdOut 改为
SLF4J，默认 INFO 不输出 SQL 参数；真实资料环境不要打开 SQL DEBUG（其中含 evidence_json）。

## 实际验证（2026-09-10）

使用 JDK 21（Microsoft 21.0.11）、已有 Maven/JUnit、H2 MySQL 模式，以及已有 Vue/jsdom 工具。
没有新增运行依赖；没有调用真实 LLM 或新增调参实验。

| 验证 | 结果 |
|---|---|
| 后端全量 `mvn -q -o -pl backend test` | 377 项：367 通过，0 failures，0 errors，10 skipped |
| SessionMaterialServiceTest | 18 项：真实 H2 事务/锁/SQL，部分 MyBatis 历史持久化；外部模型/向量为桩 |
| SessionScopedRetrievalTest | 4 项：范围与不可降级错误、正常无命中/临时故障边界 |
| MaterialDeletionEntryTest | 4 项：课程排队、读者阻止物理删除、退出后的完整删除顺序、等待不走故障重试 |
| 前端 `npm run test:session-materials` | 4 项：编译挂载 Vue，恢复原版、更新提示、失效阻断/显式新会话、准确引用/缺失提示 |
| 前端 `npm test` 与 `npm run build` | 2 项 Markdown 测试通过；类型检查及构建通过 |

代表性具名断言在 `backend/src/test/java/com/rag/backend/agent/materials/SessionMaterialServiceTest.java`：

- `oldSessionSurvivesPublicationAndServiceRestartWhileNewSessionUsesNewVersion`：原会话旧版、新会话新版、服务重建恢复。
- `firstConcurrentRequestsBindOnce`：同会话首次并发只有一份绑定。
- `cleanupAndRegistrationShareTheDocumentLock`、`retentionAndInFlightProtectionBothPrecedeReclamation`：取数与清理裁决及两层保护。
- `crashedReaderNeverExpiresJustBecauseSessionDoes`、`deletedSessionDoesNotEraseAnInFlightReader`：残留读者不因时间/会话删除消失。
- `failedExternalDeletionIsResumableWithoutReopeningAdmission`：失败保留关闭入口，可重放。
- `synchronousLateAnswerAfterWithdrawalNeverBecomesSuccessfulHistory`、`sseBuffersLateCandidateAndEmitsOnlyErrorAfterWithdrawal`：迟到结果拒绝。
- `chatPersistsVersionedReferencesAndRestoresThemWithHistory`、`referenceReadsRejectAnotherVersionAndNeverJumpToLatest`：历史及引用原版溯源。
- `failedEvidenceSerializationRollsBackBothMessages`：引用序列化失败不留下半个消息对。

全量测试最初被旧冻结文件的 Windows CRLF 检出阻塞：先发现 JSONL 校验不符，恢复其原始 LF
后又发现 manifest.json 同类问题。仅在隔离工作树恢复冻结原字节，未修改 manifest 内容、SHA
或放宽校验；最终全量通过。这些换行恢复没有源码差异，不列入功能提交。

10 个跳过项来自既有外部评测开关/输入条件：AnswerabilityDecisionSweepHarnessTest（1）、
LocalObsidianRetrievalEvalTest（1）、PublicRetrievalEvalTest（6）、RetrievalFixtureSmokeTest（1）、
ReviewedDev45RerankAblationTest（1）。不能把跳过算通过。

实际 MySQL / Milvus / LLM 端到端：**NOT RUN**。本机 Docker Desktop 启动在 Secrets Engine
socket 访问处失败，没有启动测试容器，也没有修改真实数据库。没有重置 Docker 或删除其数据。
H2 的锁测试不能替代真实 InnoDB 验收。

后续隔离环境验收清单：先确保 Docker 与 MySQL 可用，迁移 V1–V6；上传可公开的样本资料并
正常摄取激活，创建 S1 并提问，发布同文档新版，再检查 S1 仍旧版而 S2 新版；在取数与撤回/
回收交错时检查读者记录、错误和最终状态；最后核验引用打开、模型生成期间撤回、SSE 无迟到
答案，以及崩溃读者人工恢复。使用独立数据库、collection 和上传目录，不对生产资料注入故障。

## 资料库维护交接

主要补《03-Kingdom与RAG问答及源码依据.md》的 SUP-14，并更新《01-导航与简历.md》索引。
资料维护任务统一修改其 V9 源文件和导出，本任务不直接覆盖该工作区。

建议新增五个追问：激活发生在取数中途时本轮/下一轮读什么；学习连续性为何固定会话清单；
SUPERSEDED、RETIRING、撤回与物理删除如何区别；新版提示与失效/临时故障如何区分；
并发与故障测试断言如何验证三种保证。不能只写“有一条版本提示弹窗”。

| 建议源码索引 | 入口与补充依据 |
|---|---|
| R-SESSION-SCOPE | SessionMaterialService.acquire / inspect / complete，V6 三表 |
| R-RETRIEVE 更新 | RagChatService.prepare → MilvusKnowledgeRetriever.retrieveInScope；非会话旧入口边界 |
| R-DENSE-SCOPE / R-COLLECT 更新 | DenseCandidateSource、MySqlLexicalCandidateSource、DualCandidateSourceCollector 的失效传播 |
| R-MATERIAL-GC | MaterialReclamationScheduler / Service，关闭入口→等待读者→幂等删除 |
| R-DURABLE-WORKER 补全 | DeleteRequestService → DeleteJobWorker → DeleteSaga → MyBatisDeletePort；课程安全删除入口 |
| R-MATERIAL-CITATION | CitationSource、appendTurn、SessionMaterialController.reference、ReferencesList.vue |
| R-MATERIAL-UI | ChatPage.vue、metadata.materials、显式新建会话、SSE 缓冲变化 |

将“本轮设计待实现”更新为“已实现、组件验证通过”，同时保留真实 MySQL/Milvus/LLM
未运行、未部署、无多用户鉴权、默认保留参数未业务校准四类边界，不把资料维护完成等同用户验收。

## 设计参照与复用依据

- [MySQL 8.4 Locking Reads（Oracle 官方镜像）](https://docs.oracle.com/cd/E17952_01/mysql-8.4-en/innodb-locking-reads.html)：FOR UPDATE 需要事务，锁随提交/回滚释放。
- [Linux RCU 官方说明](https://docs.kernel.org/RCU/whatisRCU.html)：参考“移除新入口→等旧读者→回收”原则；没有接入内核 RCU。
- [Spring Programmatic Transaction Management](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html)：复用 TransactionTemplate 与既有 MyBatis/Spring 事务及存储接口。

没有引入分布式锁服务或新基础设施；本项目已有 MySQL 事务与持久任务足以承载本轮短事务
裁决。流程契约新建 `rag.session-materials` 并更新 `rag.question-answer` 的 flow、sequence、
state 图及源码/测试映射，最终校验记录随提交交付。
