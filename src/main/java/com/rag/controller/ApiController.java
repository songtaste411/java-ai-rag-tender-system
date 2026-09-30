package com.rag.controller;

import com.rag.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {

    private final RagService ragService;

    /** 上传文档：Python 解析 → Java 分块 → 向量入库 */
    @PostMapping(value = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "window_size", defaultValue = "800") int windowSize,
            @RequestParam(value = "overlap_size", defaultValue = "150") int overlap,
            @RequestParam(value = "chapter_min_len", defaultValue = "300") int chapterMinLen) {
        return ragService.uploadDocument(file, windowSize, overlap, chapterMinLen);
    }

    /** 纯文本入库（调试用） */
    @PostMapping("/documents/text")
    public Map<String, Object> uploadText(@RequestBody String text) {
        ragService.uploadText(text);
        return Map.of("code", 0, "msg", "ok");
    }

    /** 只检索，不调大模型 */
    @PostMapping("/search")
    public Map<String, Object> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK) {
        List<String> hits = ragService.search(query, topK);
        return Map.of("code", 0, "hits", hits);
    }

    /** RAG 问答（同步） */
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, String> body) {
        String question = body.getOrDefault("question", "");
        if (question.isBlank()) {
            return Map.of("code", 1, "msg", "question 不能为空", "answer", "");
        }
        try {
            return Map.of("code", 0, "answer", ragService.ask(question));
        } catch (Exception e) {
            return Map.of("code", 1, "msg", e.getMessage(), "answer", "");
        }
    }

    /** RAG 问答（流式） */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody Map<String, String> body) {
        String question = body.getOrDefault("question", "");
        if (question.isBlank()) {
            return Flux.just("question 不能为空");
        }
        return ragService.askStream(question);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "rag-api");
    }
}
