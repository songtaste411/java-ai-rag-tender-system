# 第 3 层：容器编排（Docker Compose）

**本机一键 & 服务器首选。** 不熟 K8s 就停在这一层。

## 本机启动

```bash
docker compose up -d --build
```

第一次拉模型：

```bash
docker exec -it ollama ollama pull bge-m3
docker exec -it ollama ollama pull deepseek-r1
```

打开：**http://localhost:18081**

常用命令：

```bash
docker compose ps
docker compose logs -f rag-api
docker compose restart rag-api
docker compose down
```

## 改代码后更新

```bash
docker compose up -d --build doc-parser   # 只改了解析
docker compose up -d --build rag-api      # 只改了 Java/网页
```

## 部署到 Linux 服务器

```bash
# 1. 拷项目
scp -r java-ai-rag-tender-system user@服务器IP:/opt/

# 2. 登录服务器
cd /opt/java-ai-rag-tender-system
docker compose up -d --build
docker exec -it ollama ollama pull bge-m3
docker exec -it ollama ollama pull deepseek-r1

# 3. 防火墙放行 18081（网页+API）
# 浏览器：http://服务器IP:18081
```

镜像拉取慢：compose 已用国内源前缀；也可 https://docker.aityp.com/ 换镜像名。

## 容器清单

| 容器 | 作用 | 端口 |
|------|------|------|
| milvus-* / redis / ollama | 基础设施 | 19530 / **6380** / 11434 |
| doc-parser | 抽文本 | 9099 |
| rag-api | 业务 + 网页 | **18081**（容器内 8081） |

## 下一层

进 Kubernetes → [04-k8s.md](04-k8s.md)
