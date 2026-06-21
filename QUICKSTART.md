# 一键启动说明

## 前提

- Windows 电脑已安装并启动 Docker Desktop。
- 首次启动需要联网拉取 Docker 镜像并构建前后端，可能需要几分钟。

## 启动

1. 解压下载包。
2. 双击 `start.bat`。
3. 脚本会自动创建 `.env`、构建并启动 MySQL、Milvus、MinIO、后端和前端。
4. 启动完成后会自动打开：

```text
http://localhost:5173
```

后端地址：

```text
http://localhost:8080
```

## 配置模型和 API Key

压缩包不会包含真实 API Key。启动后进入前端“设置”页面，配置：

- 语言模型：DeepSeek 或其他 OpenAI-compatible 模型
- Embedding：`BAAI/bge-m3`
- API Key

也可以复制 `.env.example` 为 `.env` 后手动填写：

```env
LLM_API_KEY=你的DeepSeekKey
EMBEDDING_API_KEY=你的SiliconFlowKey
```

如果使用 `BAAI/bge-m3`，保持：

```env
EMBEDDING_DIMENSION=1024
MILVUS_COLLECTION=knowledge_chunk_vectors_bge_m3
```

## 常用脚本

```text
start.bat   启动或更新启动
stop.bat    停止服务，保留数据卷
status.bat  查看容器状态
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
