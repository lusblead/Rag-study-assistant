# 当前源码快速启动

本文只描述仓库根源码树的当前启动方式。根目录 `backend/`、`frontend/` 与
`docker-compose.yml` 是开发和验收的 source of truth。

## 运行模式结论

| 模式 | 当前状态 | 入口 | 用途边界 |
|---|---|---|---|
| 完整 Docker 模式 | 默认入口存在；全新空库首启当前阻塞 | `start.bat` 或 `scripts/start.ps1` | 启动 MySQL、Milvus、etcd、MinIO、backend、frontend；后端是否就绪必须以健康检查为准 |
| 本地开发 + Docker 基础设施 | 可用 | Maven、npm 加 `docker compose` 基础设施 | 调试根源码；仍依赖 MySQL 与 Milvus |
| portable 免 Docker 模式 | 当前根源码不可用 | 无 | 仅在 `release/Rag-study-assistant-one-click/` 留有历史快照，不代表当前主链或生产验收 |

根启动脚本不会下载便携 JDK/Node，也不会切换 H2 或本地向量实现。没有 Docker Engine
时它会直接失败，不会静默降级到 portable。

> **当前已知阻塞（2026-08-09 实测）**：对一次性全新 MySQL 空库启动当前工作区镜像时，
> 应用未在 `AgentModelSettingsService` 查询前执行 Flyway，后端因缺表退出。显式运行迁移测试后
> 可以继续后续验收，但这不是启动修复。修复并重新验证前，不得把 `start.bat` 描述成已通过
> 全新环境一键启动；已有 V3 数据库仍须以 backend 健康检查和日志为准。证据见
> [`docs/acceptance/runs/2026-08-09-RAG-ISOLATED-E2E.md`](docs/acceptance/runs/2026-08-09-RAG-ISOLATED-E2E.md)。

## 完整 Docker 模式

前提：

- Windows 10/11；
- Docker Desktop / Docker Engine 已安装并正在运行；
- Docker Compose 可用；
- 首次构建和拉取镜像时具备相应网络条件。

从仓库根目录执行其一：

```text
双击 start.bat
```

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start.ps1
```

脚本实际执行 `docker compose up -d --build`，并等待前后端健康检查；后端未就绪时脚本会失败并
要求查看日志，不会把容器已创建当作应用可用。默认地址：

| 服务 | 地址 |
|---|---|
| 前端 | `http://localhost:5173` |
| 后端 | `http://localhost:8080` |
| MySQL（宿主机端口） | `localhost:3307` |
| Milvus | `localhost:19530` |
| MinIO 控制台 | `http://localhost:9001` |

停止、状态与日志：

```text
stop.bat
status.bat
logs.bat
```

`stop.bat` 执行 `docker compose down`，默认保留命名卷；它不等同于数据清理。

## 当前生产型摄取与检索主链

两个 HTTP 摄取入口：

- `POST /api/documents/{id}/ingest`
- `POST /api/agent/documents/{documentId}/ingest`

都调用同一个 `IngestApplicationService.submit`，再进入
`ReliableIngestSubmitter -> OutboxDispatcher/IngestJobPoller -> IngestJobWorker -> IngestJobOrchestrator`。
只有核验通过的 DocumentVersion 才会切换为 ACTIVE；在线
`MilvusKnowledgeRetriever` 只搜索 ACTIVE 版本，并在 MySQL 回表后再次复核版本，再交给
`DynamicKnowledgeReranker`。公开实现以
`backend/src/main/java/com/rag/backend/ingestionlab/`（提交、分发、任务与激活）和
`backend/src/main/java/com/rag/backend/agent/retrieval/MilvusKnowledgeRetriever.java` 为准；
本文不引用未发布的内部摄取流程材料。

默认 `AGENT_MOCK=false`、`VECTOR_PROVIDER=milvus`。真实模型问答还需要在环境变量或设置页
配置对应 provider 与 API Key；仅看到容器健康不代表模型调用、检索质量或生产端到端已经验收。

## 本地开发模式

前提：本机安装 JDK 21、Maven、Node.js 20+，并使用 Docker 启动基础设施。

```powershell
docker compose up -d mysql etcd minio milvus
mvn spring-boot:run -pl backend
npm --prefix frontend install
npm --prefix frontend run dev
```

后端默认连接 MySQL 与 Milvus。若显式改成 Mock 或 local provider，只能证明对应本地/Mock
路径，不可作为真实基础设施验收证据。

## portable 历史快照边界

`release/Rag-study-assistant-one-click/` 中仍保留旧的 `portable-start.ps1`、H2 profile 和旧版
源码，但该快照缺少当前 `ingestionlab` 版本化摄取、ACTIVE 版本切换及其迁移/测试。仓库根也
没有与当前主链同步的 `application-portable.yml`。因此：

- 不要用 release 快照演示或验收当前摄取主链；
- 不要把 `scripts/package-release.ps1` 的存在当作 portable 已重新验证；
- portable 重新可用需要单独完成 profile、schema/migration、版本化本地向量端口、启动脚本
  和进程级 E2E 的同步与验收。

## 不启动服务的静态检查

下列命令不启动 Docker、数据库或模型：

```powershell
# PowerShell 脚本语法
$tokens = $null
$errors = $null
[System.Management.Automation.Language.Parser]::ParseFile(
  (Resolve-Path scripts/start.ps1), [ref]$tokens, [ref]$errors
) | Out-Null
if ($errors.Count -gt 0) { $errors | Format-List; exit 1 }

# 后端离线测试（依赖须已在本机 Maven 缓存）
mvn -o test -pl backend
```

可执行但不冒充已验收的生产检查步骤见
[`docs/acceptance/RAG_PRODUCTION_E2E_CHECKLIST.md`](docs/acceptance/RAG_PRODUCTION_E2E_CHECKLIST.md)。
