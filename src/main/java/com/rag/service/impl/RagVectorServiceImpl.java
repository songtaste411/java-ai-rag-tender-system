package com.rag.service.impl;

import com.rag.service.RagVectorService;
import jakarta.annotation.Resource;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class RagVectorServiceImpl implements RagVectorService {

    @Resource
    private MilvusVectorStore milvusVectorStore;

    @Override
    public void saveAll(List<String> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        List<Document> docs = chunks.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(c -> Document.builder()
                        .id(UUID.randomUUID().toString())
                        .text(c)
                        .build())
                .toList();
        if (!docs.isEmpty()) {
            milvusVectorStore.add(docs);
        }
    }

    @Override
    public List<String> search(String query, int topK) {
        int k = Math.max(1, Math.min(topK, 20));
        return milvusVectorStore.similaritySearch(
                        SearchRequest.builder().query(query).topK(k).build())
                .stream()
                .map(Document::getText)
                .toList();
    }
}
