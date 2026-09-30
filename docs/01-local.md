# 第 1 层：本地 / 打包运行

适合：**改代码、调试**。依赖用 Docker，业务在本机跑。

## 准备

- Docker Desktop
- Python 3.10+
- JDK 21（没有就用第 3 层跑 Java）

## 步骤

### 1）只起依赖

```bash
docker compose up -d etcd minio standalone redis ollama
```

首次拉模型（只需一次）：

```bash
docker exec -it ollama ollama pull bge-m3
docker exec -it ollama ollama pull deepseek-r1
```

### 2）起解析服务

```bash
cd document-parser
pip install -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple
python main.py
```

检查：http://localhost:9099/health

### 3）起 Java（网页 + API）

IDEA 运行 `RagApplication`，或：

```bash
# 需本机 JDK21；没有请改用 docs/03-compose.md
mvn -DskipTests spring-boot:run
```

打开：**http://localhost:8081**

- 导入：`/index.html`
- 问答：`/chat.html`

### 4）打包成 jar（仍连本机依赖）

```bash
# 用 Docker 内 JDK21 打包（本机 Java8 也行）
docker build -t rag-api:latest .
docker run --rm -p 8081:8081 --add-host=host.docker.internal:host-gateway ^
  -e PARSER_BASE_URL=http://host.docker.internal:9099 ^
  -e OLLAMA_BASE_URL=http://host.docker.internal:11434 ^
  -e MILVUS_HOST=host.docker.internal ^
  -e REDIS_HOST=host.docker.internal ^
  rag-api:latest
```

（Linux 把 `^` 换成 `\`；或直接用第 3 层 Compose。）

## 默认端口

| 服务 | 端口 |
|------|------|
| 网页 / Java API | 8081 |
| Python 解析 | 9099 |
| Ollama | 11434 |
| Milvus | 19530 |
| Redis | **6380**（容器内 6379；本机 Java 请设 `REDIS_PORT=6380`） |

## 下一层

打业务镜像 → [02-image.md](02-image.md)  
一键全栈 / 上服务器 → [03-compose.md](03-compose.md)
