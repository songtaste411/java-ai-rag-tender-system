package com.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class RagApplication {
    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
        System.out.println("""
            ==================================================
            招投标 RAG 智能问答系统
            --------------------------------------------------
            Milvus Connected | AI Engine Ready
            System Running
            ==================================================
            """);
    }
}