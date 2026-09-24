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
    /**
     * @deprecated 【已废弃】
     * 原Java端文件解析、分块、向量化、Milvus入库逻辑全部迁移至 Python document-parser 服务
     * 新方案：前端直接调用 Python 接口完成 文件上传→解析→混合分块→向量入库
     * Java 仅作为上层业务层，按需调用 Python 检索接口，不再处理文件解析与存储
     */
    @SneakyThrows
    @PostMapping("/upload")
    @Deprecated
    public String upload(@RequestParam("file") MultipartFile file) {
        ragService.uploadFile(file);
        return "文档已解析：" + file.getOriginalFilename();
    }
    @PostMapping(value = "/askStream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> askStream(@RequestBody String question) {
        return ragService.askStream(question);
    }

}