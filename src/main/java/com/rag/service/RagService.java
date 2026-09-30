package com.rag.service;

import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

public interface RagService {

    Map<String, Object> uploadDocument(MultipartFile file, int windowSize, int overlap, int chapterMinLen);

    void uploadText(String content);

    String ask(String question);

    Flux<String> askStream(String question);

    List<String> search(String question, int topK);
}
