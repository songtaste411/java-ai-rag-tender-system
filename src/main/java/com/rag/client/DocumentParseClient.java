package com.rag.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 调用 Python 文档解析服务。
 * 使用 JDK HttpClient 手写 multipart，避免 Spring RestClient 丢 boundary 导致 FastAPI 422。
 */
@Component
public class DocumentParseClient {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public DocumentParseClient(
            @Value("${app.parser.base-url:http://127.0.0.1:9099}") String baseUrl,
            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public String parseToText(String filename, byte[] bytes) {
        String safeName = (filename == null || filename.isBlank()) ? "upload.bin" : filename;
        String boundary = "----ragBoundary" + UUID.randomUUID().toString().replace("-", "");

        byte[] body = buildMultipart(boundary, safeName, bytes);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/parse"))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new IllegalStateException(response.statusCode() + " " + response.body());
            }
            Map<String, Object> resp = objectMapper.readValue(response.body(), new TypeReference<>() {});
            Object code = resp.get("code");
            if (code instanceof Number n && n.intValue() != 0) {
                throw new IllegalStateException(String.valueOf(resp.get("msg")));
            }
            Object text = resp.get("text");
            if (text == null || text.toString().isBlank()) {
                throw new IllegalStateException(String.valueOf(resp.getOrDefault("msg", "解析结果为空")));
            }
            return text.toString();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("调用解析服务失败: " + e.getMessage(), e);
        }
    }

    private static byte[] buildMultipart(String boundary, String filename, byte[] fileBytes) {
        String asciiName = filename.replace("\"", "_");
        List<byte[]> parts = new ArrayList<>();
        parts.add(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        parts.add(("Content-Disposition: form-data; name=\"file\"; filename=\"" + asciiName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        parts.add("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        parts.add(fileBytes);
        parts.add(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        int total = parts.stream().mapToInt(p -> p.length).sum();
        byte[] all = new byte[total];
        int offset = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, all, offset, p.length);
            offset += p.length;
        }
        return all;
    }
}
