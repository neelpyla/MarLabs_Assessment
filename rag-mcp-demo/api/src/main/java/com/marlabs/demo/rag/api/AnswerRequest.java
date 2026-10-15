package com.marlabs.demo.rag.api;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;

public record AnswerRequest(
        @NotBlank String question,
        @NotNull LocalDate asOf) {
}
