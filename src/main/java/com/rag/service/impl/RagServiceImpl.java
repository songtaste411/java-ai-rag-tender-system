package com.rag.service.impl;

import com.rag.chunk.TextChunker;
import com.rag.client.DocumentParseClient;
import com.rag.service.RagService;
import com.rag.service.RagVectorService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RagServiceImpl implements RagService {

    private final ChatClient chatClient;
    private final RagVectorService ragVectorService;
    private final DocumentParseClient documentParseClient;

    public RagServiceImpl(
            ChatClient.Builder chatClientBuilder,
            RagVectorService ragVectorService,
            DocumentParseClient documentParseClient) {
        this.chatClient = chatClientBuilder.build();
        this.ragVectorService = ragVectorService;
        this.documentParseClient = documentParseClient;
    }

    @Override
    public Map<String, Object> uploadDocument(MultipartFile file, int windowSize, int overlap, int chapterMinLen) {
        try {
            String filename = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
            String text = documentParseClient.parseToText(filename, file.getBytes());
            List<String> chunks = TextChunker.hybridChunk(text, windowSize, overlap, chapterMinLen);
            ragVectorService.saveAll(chunks);

            Map<String, Object> result = new HashMap<>();
            result.put("code", 0);
            result.put("msg", "导入成功");
            result.put("filename", filename);
            result.put("chars", text.length());
            result.put("chunkCount", chunks.size());
            result.put("chunks", chunks);
            return result;
        } catch (Exception e) {
            Map<String, Object> err = new HashMap<>();
            err.put("code", 1);
            err.put("msg", e.getMessage());
            err.put("chunkCount", 0);
            err.put("chunks", List.of());
            return err;
        }
    }

    @Override
    public void uploadText(String content) {
        List<String> chunks = TextChunker.hybridChunk(content, 800, 150, 300);
        if (chunks.isEmpty() && content != null && !content.isBlank()) {
            chunks = List.of(content);
        }
        ragVectorService.saveAll(chunks);
    }

    @Override
    public List<String> search(String question, int topK) {
        return ragVectorService.search(question, topK);
    }

    @Override
    public String ask(String question) {
        List<String> contextList = ragVectorService.search(question, 5);
        String context = String.join("\n---\n", contextList);
        String prompt = buildPrompt(context, question);
        return chatClient.prompt()
                .user(prompt)
                .options(OllamaOptions.builder().temperature(0.1).numPredict(1024).build())
                .call()
                .content();
    }

    @Override
    public Flux<String> askStream(String question) {
        return Flux.defer(() -> {
            List<String> contextList = ragVectorService.search(question, 5);
            String context = String.join("\n---\n", contextList);
            String prompt = buildPrompt(context, question);
            return chatClient.prompt()
                    .user(prompt)
                    .options(OllamaOptions.builder().temperature(0.1).numPredict(1024).build())
                    .stream()
                    .content();
        }).onErrorResume(e -> Flux.just("出错：" + e.getMessage()));
    }

    private static String buildPrompt(String context, String question) {
        return """
                你是招投标知识库助手。只根据【资料】回答，不要编造；资料不足就说不知道。用完整中文回答。

                【资料】
                %s

                【问题】
                %s
                """.formatted(context.isBlank() ? "（无检索结果）" : context, question);
    }
}
