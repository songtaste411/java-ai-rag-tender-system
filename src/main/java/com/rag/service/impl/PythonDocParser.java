package com.rag.service.impl;

import com.rag.model.ParseResponse;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class PythonDocParser {


    // 调用 Python 解析 → 直接回调保存（和你 FileUtils 一模一样）
    public void parseAndSave(String filename, InputStream in, int chunkSize, Consumer<String> consumer) {
        try {
            System.out.println("=== Java 开始调用 Python ===");

            byte[] bytes = in.readAllBytes();
            ByteArrayResource resource = new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };

            MultiValueMap<String, Object> params = new LinkedMultiValueMap<>();
            params.add("file", resource);
            params.add("chunk_size", chunkSize);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(params, headers);

            RestTemplate restTemplate = new RestTemplate();
            restTemplate.getInterceptors().clear();

            // ✅ 正确接收
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    "http://127.0.0.1:9099/parse-document",
                    requestEntity,
                    Map.class
            );

            Map<String, Object> result = response.getBody();
            System.out.println("=== Python 返回：" + result + " ===");

            List<String> chunks = (List<String>) result.get("chunks");

            if (chunks != null && !chunks.isEmpty()) {
                chunks.forEach(consumer);
            }

        } catch (Exception e) {
            System.err.println("=== Python 调用异常 ===");
            e.printStackTrace();
        }
    }

}