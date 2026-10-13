package com.MLab.assessment.model;

import java.util.List;

public record BatchResponse(String batch_id, BatchSummary summary, List<BatchItemResult> results) {
}
