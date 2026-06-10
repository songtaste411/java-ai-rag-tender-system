package com.rag.utils;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.apache.poi.hwpf.HWPFDocument;

public final class FileUtils {

    @FunctionalInterface
    public interface ChunkHandler {
        void handle(String chunk);
    }

    public static void processFile(String fileName, InputStream in, int chunkSize, ChunkHandler handler) {
        try {
            System.out.println("进入解析，扩展名：" + getFileExtension(fileName));
            
            String ext = getFileExtension(fileName);
            switch (ext) {
                case "txt", "md", "log" -> readText(in, chunkSize, handler);
                case "pdf" -> readPdf(in, chunkSize, handler);
                case "docx" -> readDocx(in, chunkSize, handler);
                case "doc" -> readDoc(in, chunkSize, handler);
                default -> throw new UnsupportedOperationException("不支持的文件类型");
            }

        } catch (Exception e) {
            // 强制打印错误！
            e.printStackTrace();
            throw new RuntimeException("文件解析失败", e);
        }
    }

    private static void readText(InputStream in, int size, ChunkHandler h) throws Exception {
        System.out.println("解析 TXT 文件...");
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        int count = 0;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (!line.isBlank()) {
                h.handle("TXT第" + (++count) + "行：" + line);
            }
        }
        System.out.println("TXT解析完成，共" + count + "行");
    }

    private static void readPdf(InputStream in, int size, ChunkHandler h) throws Exception {
        try (var doc = Loader.loadPDF(new RandomAccessReadBuffer(in))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            String fullText = stripper.getText(doc).replaceAll("\\s+", " ").trim();
            splitText(fullText, size, h);
        }
    }
    private static void readDoc(InputStream in, int chunkSize, ChunkHandler h) throws Exception {
        try (HWPFDocument doc = new HWPFDocument(in)) {
            String fullText = doc.getText().toString().replaceAll("\\s+", " ").trim();
            splitText(fullText, chunkSize, h); // 🔥 调用公共分块
        }
    }
    private static void readDocx(InputStream in, int size, ChunkHandler h) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(in)) {
            XWPFWordExtractor extractor = new XWPFWordExtractor(doc);
            String fullText = extractor.getText().replaceAll("\\s+", " ").trim();
            splitText(fullText, size, h); // 🔥 调用公共分块
        }
    }

    private static String getFileExtension(String fileName) {
        return fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
    }
    /**
     * 通用文本分块（和你PDF逻辑完全一致！）
     * 不断句、不断词、中英兼容、代码兼容
     */
    private static void splitText(String fullText, int chunkSize, ChunkHandler h) {
        if (fullText == null || fullText.isBlank()) return;

        StringBuilder chunk = new StringBuilder();
        // 按句子分割（中英文句号/感叹号/问号）
        String[] sentences = fullText.split("(?<=[。！？.!?])");

        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.isBlank()) continue;

            // 超过大小就输出
            if (chunk.length() + sentence.length() > chunkSize) {
                if (!chunk.isEmpty()) {
                    h.handle(chunk.toString().trim());
                    chunk.setLength(0);
                }

                // 超长句子整块输出，不硬切
                if (sentence.length() > chunkSize) {
                    h.handle(sentence.trim());
                } else {
                    chunk.append(sentence).append(" ");
                }
            } else {
                chunk.append(sentence).append(" ");
            }
        }

        // 输出最后一块
        if (!chunk.isEmpty()) {
            h.handle(chunk.toString().trim());
        }
    }
}