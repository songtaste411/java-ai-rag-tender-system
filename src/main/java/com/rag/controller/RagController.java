package com.rag.controller;

import com.rag.service.RagService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagService ragService;

    @PostMapping("/uploadText")
    public String uploadText(@RequestBody String text) {
        ragService.uploadText(text);
        return "文本上传成功";
    }
    @PostMapping("/query")
    public String query(@RequestBody String question) {
        return ragService.ask(question);
    }
    @SneakyThrows
    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file) {
        ragService.uploadFile(file);
        return "文档已解析：" + file.getOriginalFilename();
    }
    @PostMapping(value = "/askStream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> askStream(@RequestBody String question) {
        return ragService.askStream(question);
    }

}