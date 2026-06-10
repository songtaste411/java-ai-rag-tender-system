package com.rag.model;

import lombok.Data;
import java.util.List;

@Data
public class ParseResponse {
    private Integer code;
    private List<String> chunks;
}