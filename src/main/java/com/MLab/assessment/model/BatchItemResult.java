package com.MLab.assessment.model;

import java.util.List;
import java.util.Map;

public record BatchItemResult(
        String document_id,
        String processing_status,
        ExtractedFields extracted,
        Map<String, FieldEvidence> field_evidence,
        AnswerResponse policy,
        boolean review_required,
        List<String> issues,
        String duplicate_of,
        ItemError error
) {
}
