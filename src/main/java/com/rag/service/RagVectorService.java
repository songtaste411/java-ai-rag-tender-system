package com.rag.service;

import java.util.List;

public interface RagVectorService {

    void saveAll(List<String> chunks);

    List<String> search(String query, int topK);
}
