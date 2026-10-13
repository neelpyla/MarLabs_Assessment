package com.MLab.assessment.model;

import java.util.List;

public record BatchManifest(String batch_id, String as_of, List<BatchDocument> documents) {
}
