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
