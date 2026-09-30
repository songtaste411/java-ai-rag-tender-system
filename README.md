# 招投标知识库 RAG

> 学习 / 自用项目均可。请勿商用未授权数据。

## 架构（一句话）

**Python 只抽文本 → Java 分块/向量/检索/问答 → 浏览器只打 Java**

```
PDF/DOCX → document-parser(:9099) → 纯文本
                                      ↓
              rag-api(:8081) 分块 → Ollama(bge-m3) → Milvus
                                      ↓
              问答：检索 + Ollama(deepseek-r1) → 网页/API
```

## 四层部署（互联网演进，选一层或从上往下递进）

| 层 | 文档 | 你在干什么 |
|----|------|------------|
| **1 本地/打包** | [docs/01-local.md](docs/01-local.md) | 依赖容器化，业务本机跑，改代码最快 |
| **2 容器化** | [docs/02-image.md](docs/02-image.md) | 把业务打成镜像 |
| **3 容器编排** | [docs/03-compose.md](docs/03-compose.md) | `docker compose up` 一键全栈 / **服务器首选** |
| **4 K8s** | [docs/04-k8s.md](docs/04-k8s.md) | 一条脚本：`k8s/ops.ps1 -Action up` |

**不熟 Docker？直接看第 3 层。**

## 常用入口

| 用途 | 地址 |
|------|------|
| 导入 / 问答网页 | http://localhost:18081（Compose） / http://localhost:8081（本机跑 Java） |
| 解析服务健康检查 | http://localhost:9099/health |
| Ollama | http://localhost:11434/api/tags |

## 目录

```
docs/                 四层说明书（只看这里）
document-parser/      Python 解析微服务
src/                  Java RAG API + 静态网页
docker-compose.yml    第1层依赖 / 第3层全栈
Dockerfile            Java 镜像
k8s/                  第4层清单 + ops.ps1
```

## 最短路径（推荐 Compose）

```bash
docker compose up -d --build
docker exec -it ollama ollama pull bge-m3
docker exec -it ollama ollama pull deepseek-r1
# 打开 http://localhost:18081
```
