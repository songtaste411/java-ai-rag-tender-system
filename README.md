# 本项目仅作学习交流使用，请勿用于商业用途。
# 招投标知识库 RAG 智能问答系统
基于 Java + Spring AI + LangChain4j + Milvus 构建的企业级 RAG 项目
用于招标文件智能解析、语义检索、智能问答、多轮对话

## 技术栈
- Java 21
- Spring Boot 3
- Spring AI
- LangChain4j
- Milvus 向量数据库
- Redis 缓存
- Ollama / DeepSeek 本地大模型
- Python (文档解析)

## 核心功能
1. PDF / Word 文档解析与分块
2. 混合分块策略（章节 + 滑动窗口）
3. Embedding 向量化 + Milvus 存储
4. 向量 + BM25 双路检索
5. Re-ranking 重排序
6. 多轮对话上下文缓存
7. 本地大模型部署与调用

## 项目架构
文档解析 → 分块 → 向量化 → 向量库 → 多路召回 → 大模型生成答案
## 本地调试说明

1. 启动容器  
   `docker compose up -d`  
   确保容器启动成功：`docker ps`  
   镜像搜索地址：https://docker.aityp.com/
2. **必须等 Ollama 就绪**（端口 `11434`）  
   - 文档解析分块会调用 Ollama 的 `/api/embeddings`（模型：`bge-m3`）  
   - 检查是否通：浏览器打开 `http://localhost:11434` 或执行  
     `curl http://localhost:11434/api/tags`  
   - 若报错 `WinError 10061 / 连接被拒绝`：说明 Ollama 没起来，先看日志  
     `docker logs ollama --tail 50`  
   - 首次启动会自动拉取模型，`bge-m3` 拉完后即可做「解析分块」；`deepseek-r1` 较大，可稍后完成
3. 本地启动 `RagApplication`（Java 后端问答）
4. **启动文档解析前端页面**（见下方专节）
5. 关闭容器：`docker compose down`

## 文档解析前端启动方法

本项目的「前端」不是单独的 Vue/React，而是 Python 服务自带的网页：  
`document-parser/static/index.html`，由 FastAPI 一起启动。

### 方式一：本地启动（推荐调试，端口 9100）

前置：已安装 Python 3.10+，且 Milvus、Ollama 已按上面步骤启动。

```bash
cd document-parser
pip install -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple
python main.py
```

浏览器打开：**http://localhost:9100**

- **文档导入页**：http://localhost:9100/static/index.html  
  上传 PDF/DOCX，解析分块并写入知识库  
- **知识库问答页**：http://localhost:9100/static/chat.html  
  提问后先检索已导入文档；对话模型 `deepseek-r1` 就绪后可自动生成回答  

页面标题为「PDF / DOCX 解析 + 章节 + 滑动窗口分块」，选文件后点「开始解析分块」即可。

等价命令（效果相同）：

```bash
cd document-parser
uvicorn main:app --host localhost --port 9100
```

停止服务：在运行该命令的终端按 `Ctrl+C`。

### 方式二：Docker 启动（端口 9099）

```bash
docker compose up -d doc-parser
```

浏览器打开：**http://localhost:9099**

- 导入：http://localhost:9099/static/index.html  
- 问答：http://localhost:9099/static/chat.html  

> 注意端口不同：本地 `python main.py` → **9100**；Docker → **9099**。

### 使用流程（导入 → 问答）

1. 先打开「文档导入」页，上传文件并等到提示入库成功  
2. 再打开「知识库问答」页输入问题  
3. 可点「只检索知识库」立刻看相关段落；勾选「同时让 AI 回答」需等 `deepseek-r1` 下载完成（可用 `docker exec ollama ollama list` 查看）  
4. **CPU 跑 deepseek-r1 很慢**：首次提问可能要 **5～15 分钟**，页面会先显示检索结果再等 AI；若仍超时，先用「只检索」看资料，或换有 GPU 的机器 / 更小的对话模型

## 常见问题

| 现象 | 原因 | 解决 |
|------|------|------|
| 失败：连接 localhost:11434 被拒绝 | Ollama 未运行或容器在重启 | `docker compose up -d ollama`，再查 `docker logs ollama` |
| Ollama 日志：`unknown command "sh"` | compose 未覆盖 entrypoint | 已修复：需使用带 `entrypoint: ["/bin/sh","-c"]` 的 compose |
| embeddings 报 model not found | 还没拉到 `bge-m3` | 等自动 pull，或手动：`docker exec -it ollama ollama pull bge-m3` |
| 控制台刷 `Could not get FontBBox...` | PDF 字体信息不完整，pdfminer 警告 | 可忽略；已在 `document-parser` 屏蔽该刷屏 |
| `GET /.well-known/... 404` | Chrome 开发者工具探测，与业务无关 | 可忽略 |
| 打开网页连不上 | 没启动 `document-parser`，或端口搞错 | 本地用 `9100`，Docker 用 `9099` |
| `pip` / `pymilvus` 安装失败 | 依赖未装好 | 在 `document-parser` 目录重跑 `pip install -r requirements.txt` |

