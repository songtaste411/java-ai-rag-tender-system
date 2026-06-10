package com.rag.service;

import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

public interface RagService {
    void uploadText(String content);

    String ask(String question);

    Flux<String> askStream(String question);

    /**
     * 上传文件（支持 TXT / MD / PDF / DOCX ）
     */
    void uploadFile(MultipartFile file) throws Exception;
}