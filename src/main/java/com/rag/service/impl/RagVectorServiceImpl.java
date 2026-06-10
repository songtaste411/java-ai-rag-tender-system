package com.rag.service.impl;

import com.rag.service.RagVectorService;
import jakarta.annotation.Resource;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RagVectorServiceImpl implements RagVectorService {

    // 🔥 Spring AI 官方 Bean，直接用！
    @Resource
    private MilvusVectorStore milvusVectorStore;

    /**
     * 保存文本分块（自动生成向量、自动存入 Milvus）
     */
    @Override
    public void save(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("内容不能为空");
        }
        milvusVectorStore.add(List.of(new Document(content)));
        System.out.println("✅ 自动入库 Milvus：" + content.substring(0, 30) + "...");
    }

    @Override
    public List<String> search(String query) {
        return milvusVectorStore.similaritySearch(query)
                .stream()
                .map(Document::getText)
                .toList();
    }
}