# 一键启动说明

## 前提

- Windows 10/11。
- 能联网。
- 不需要安装 Docker Desktop、MySQL、Milvus、Java、Node 或 Maven。

首次启动时，`start.bat` 会自动下载便携 JDK 21。若下载包里没有预构建产物，它还会自动下载便携 Node.js 20，并用 Maven Wrapper/Node 构建后端和前端。

## 启动

1. 解压下载包。
2. 双击 `start.bat`。
3. 等待脚本完成启动。
4. 浏览器会自动打开：

```text
http://localhost:8080
```

## 默认运行模式

一键启动使用 portable 模式：

- 数据库：本地 H2 文件数据库，数据保存在 `data/`。
- 向量检索：本地向量/词法检索，不需要 Milvus。
- 文件上传：保存在 `data/uploads/`。
- 前端：由后端直接静态托管，不需要单独 Node 服务。

## 配置模型和 API Key

压缩包不会包含真实 API Key。启动后进入前端“设置”页面，配置：

- 语言模型：DeepSeek 或其他 OpenAI-compatible 模型
- Embedding：可用本地模式，也可配置 `BAAI/bge-m3`
- API Key

默认 portable 模式可使用本地 LLM/Embedding 占位能力跑通流程；要展示真实回答质量，请在设置页填入真实模型和 key。

也可以复制 `.env.example` 为 `.env` 后手动填写：

```env
LLM_PROVIDER=deepseek
LLM_API_KEY=你的DeepSeekKey
EMBEDDING_PROVIDER=openai-compatible
EMBEDDING_API_KEY=你的SiliconFlowKey
```

如果使用 `BAAI/bge-m3`，保持：

```env
EMBEDDING_DIMENSION=1024
```

## 常用脚本

```text
start.bat   启动应用
stop.bat    停止应用
status.bat  查看状态
logs.bat    查看实时日志
```

## 重新打包

在项目根目录运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/package-release.ps1
```

生成的 zip 位于：

```text
release/Rag-study-assistant-one-click.zip
```

## Docker 可选

`docker-compose.yml` 仍然保留，适合开发或需要 MySQL + Milvus 的完整部署；但默认一键启动不依赖 Docker。
