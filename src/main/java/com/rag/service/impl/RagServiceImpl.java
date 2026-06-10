package com.rag.service.impl;

import com.rag.service.RagService;
import com.rag.service.RagVectorService;
import com.rag.utils.FileUtils;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.io.File;
import java.io.FileInputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
public class RagServiceImpl implements RagService {
    // 1. 加这个注解注入配置
    @Value("${python.parser.enabled:false}")
    private boolean pythonParserEnabled;

    private final ChatClient chatClient;
    private final RagVectorService ragVectorService;
    private final PythonDocParser pythonDocParser;


    // 构造器注入
    public RagServiceImpl(ChatClient.Builder chatClientBuilder, RagVectorService ragVectorService1, PythonDocParser pythonDocParser) {
        this.chatClient = chatClientBuilder.build();
        this.ragVectorService = ragVectorService1;
        this.pythonDocParser = pythonDocParser;
    }

    @Override
    public void uploadText(String content) {
        ragVectorService.save(content);
    }

    @Override
    public String ask(String question) {
        try {
            // 1. 检索
            List<String> contextList = ragVectorService.search(question);
            String context = String.join("\n---\n", contextList);

            // 2. 直接用 chatClient 链式，不会爆红
            return chatClient.prompt()
                    .user("你是专业助手，请根据资料严谨回答，不要编造。\n资料：\n" + context + "\n问题：" + question)
                    .options(OllamaOptions.builder()
                            .temperature(0.1)
                            .numPredict(800)
                            .build())
                    .stream()
                    .content()
                    .collectList()
                    .map(list -> String.join("", list))
                    .block();

        } catch (Exception e) {
            e.printStackTrace();
            return "调用出错：" + e.getMessage();
        }
    }
    @Override
    public Flux<String> askStream(String question) {
        return Flux.defer(() -> {
            // 1. 检索上下文
            List<String> contextList = ragVectorService.search(question);
            String context = String.join("\n---\n", contextList);

            // 2. 构建 prompt
            String promptText = "你是专业助手，请根据资料严谨回答，不要编造。\n"
                    + "资料：\n" + context + "\n问题：" + question;

            // 3. 流式调用（用旧版 OllamaOptions，不爆红）
            return chatClient.prompt()
                    .user(promptText)
                    .options(OllamaOptions.builder()
                            .temperature(0.1D)
                            .numPredict(800) // Ollama 原生就是 numPredict
                            .build())
                    .stream()
                    .content();

        }).onErrorResume(e -> {
            e.printStackTrace();
            return Flux.just("出错：" + e.getMessage());
        });
    }
    /**
     * 异步上传文件 → 解析 → 分片 → 向量化 → 入库
     */
    @Async
    @Override
    public void uploadFile(MultipartFile file) {
        try {
            System.out.println("===== 异步开始处理 =====");
            System.out.println("文件名：" + file.getOriginalFilename());
            System.out.println("文件大小：" + file.getSize());

            // 1. 创建目录
            String base = System.getProperty("user.home") + "/data/tmp/rag_uploads/";
            String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            File dir = new File(base + date);
            if (!dir.exists()) dir.mkdirs();

            // 2. 保存文件
            File target = new File(dir, System.currentTimeMillis() + "_" + file.getOriginalFilename());
            file.transferTo(target.getAbsoluteFile());
            System.out.println("文件已保存到：" + target.getAbsolutePath());

            // 3. 直接解析本地文件（强制输出，绝对跑）
            try (FileInputStream in = new FileInputStream(target)) {
                System.out.println("开始解析文件...");
                if (pythonParserEnabled) {
                    // 走 Python 解析 → 只打印日志，不保存！
                    pythonDocParser.parseAndSave(
                            file.getOriginalFilename(),
                            in,
                            512,
                            chunk -> {
                                // 🔥 只打印，不存库！调试超快！
                                System.out.println("【Python 解析成功】：" + chunk);
                                ragVectorService.save(chunk);
                            }
                    );
                } else {
                    // 默认 Java 解析 → 也只打印，不保存！
                    FileUtils.processFile(target.getName(), in, 512, chunk -> {
                        System.out.println("【Java 解析成功】：" + chunk);
                        ragVectorService.save(chunk);
                    });
                }
                System.out.println("===== 解析完成 =====");
            }

        } catch (Exception e) {
            // 【关键】把错误打出来！！！
            e.printStackTrace();
            System.out.println("处理失败：" + e.getMessage());
        }
    }
    // 调用 Python 解析文件
    public String parseDocument(MultipartFile file) {
        String url = "http://python-parser:9999/parse-document";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", file.getResource());

        HttpEntity<LinkedMultiValueMap<String, Object>> request =
                new HttpEntity<>(body, headers);

        RestTemplate rest = new RestTemplate();
        var map = rest.postForObject(url, request, Map.class);

        return (String) map.get("content");
    }
}