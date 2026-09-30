# 第 2 层：容器化（只构建镜像）

把业务打成镜像，**还不编排**。方便你推到服务器或进 K8s。

## 构建

在项目根目录：

```bash
# 解析服务
docker build -t rag-doc-parser:latest ./document-parser

# Java API + 网页
docker build -t rag-api:latest .
```

查看：

```bash
docker images | findstr rag
```

## 单独跑一个容器（需依赖已在跑）

假设本机已 `docker compose up -d etcd minio standalone redis ollama`：

```bash
docker run -d --name doc-parser -p 9099:9099 --network rag rag-doc-parser:latest

docker run -d --name rag-api -p 8081:8081 --network rag ^
  -e PARSER_BASE_URL=http://doc-parser:9099 ^
  -e OLLAMA_BASE_URL=http://ollama:11434 ^
  -e MILVUS_HOST=milvus-standalone ^
  -e REDIS_HOST=redis ^
  rag-api:latest
```

> 网络名是 `rag`（见 docker-compose.yml）。若网络不存在，先起过一次 compose。

## 导出给服务器（不重下代码构建时）

```bash
docker save rag-api:latest rag-doc-parser:latest -o rag-images.tar
# 拷到服务器后：
docker load -i rag-images.tar
```

## 下一层

用 Compose 一次拉起全部 → [03-compose.md](03-compose.md)
