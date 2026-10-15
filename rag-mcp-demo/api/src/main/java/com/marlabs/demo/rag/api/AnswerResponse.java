package com.marlabs.demo.rag.api;

import java.util.List;

public record AnswerResponse(String status, String answer, List<Citation> citations) {

    public record Citation(String policyId, String quote, String effectiveFrom, String effectiveTo) {
    }
}
