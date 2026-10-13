package com.MLab.assessment.model;

import java.math.BigDecimal;

public record ExtractedFields(String benefit, BigDecimal amount, String currency, String reference) {
}
