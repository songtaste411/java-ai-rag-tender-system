package com.rag.service;

import java.util.List;

/**
 * @author qiyong
 * @description: TODO
 * @date 2026/6/9
 */
public interface RagVectorService {
    void save(String chunk);

    List<String> search(String query);
}
