package com.MLab.assessment.model;

import jakarta.validation.constraints.NotBlank;

public record AnswerRequest(
        @NotBlank String question,
        @NotBlank String as_of
) {
}
