package com.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RagApplication {
    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
        System.out.println("""
                ==================================================
                招投标 RAG | API http://localhost:8081
                导入页 /index.html | 问答页 /chat.html
                ==================================================
                """);
    }
}
