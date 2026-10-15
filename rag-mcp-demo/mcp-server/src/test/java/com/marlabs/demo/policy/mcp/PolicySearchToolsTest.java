package com.marlabs.demo.policy.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

class PolicySearchToolsTest {

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final PolicySearchTools tools = new PolicySearchTools(vectorStore, new ObjectMapper());

    @Test
    void returnsOnlyEvidenceEffectiveOnRequestedDate() throws Exception {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                document("current-policy", "2026-01-01", "2027-01-01"),
                document("expired-policy", "2025-01-01", "2026-01-01")));

        JsonNode result = new ObjectMapper().readTree(
                tools.searchPolicies("certification reimbursement", "atlas", "employee", "2026-09-21"));

        assertEquals(1, result.size());
        assertEquals("current-policy", result.get(0).get("policyId").asText());
    }

    private Document document(String id, String effectiveFrom, String effectiveTo) {
        return new Document(id, "Policy evidence", Map.of(
                "policy_id", id,
                "effective_from", effectiveFrom,
                "effective_to", effectiveTo));
    }
}
