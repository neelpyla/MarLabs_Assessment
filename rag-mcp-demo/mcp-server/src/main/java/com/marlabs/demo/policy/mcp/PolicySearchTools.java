package com.marlabs.demo.policy.mcp;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

@Component
public class PolicySearchTools {

    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;

    public PolicySearchTools(VectorStore vectorStore, ObjectMapper objectMapper) {
        this.vectorStore = vectorStore;
        this.objectMapper = objectMapper;
    }

    @McpTool(
            name = "search_policies",
            description = "Search policy passages for the supplied trusted tenant, role, and effective date.")
    public String searchPolicies(
            @McpToolParam(description = "Natural-language question or search phrase") String query,
            @McpToolParam(description = "Trusted tenant identifier, for example atlas") String tenant,
            @McpToolParam(description = "Trusted caller role, for example employee") String role,
            @McpToolParam(description = "Date on which the policy must be effective, in YYYY-MM-DD format") String asOf) {
        LocalDate requestedDate = LocalDate.parse(asOf);
        String filter = "tenant == '" + escapeFilterValue(tenant) + "' && role == '"
                + escapeFilterValue(role) + "'";
        List<Document> matches = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(20)
                .filterExpression(filter)
                .build());

        List<PolicyEvidence> evidence = matches.stream()
                .filter(document -> effectiveOn(document.getMetadata(), requestedDate))
                .map(this::toEvidence)
                .toList();
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize policy evidence for the MCP response", exception);
        }
    }

    private boolean effectiveOn(Map<String, Object> metadata, LocalDate date) {
        LocalDate effectiveFrom = LocalDate.parse((String) metadata.get("effective_from"));
        LocalDate effectiveTo = LocalDate.parse((String) metadata.get("effective_to"));
        return !date.isBefore(effectiveFrom) && date.isBefore(effectiveTo);
    }

    private PolicyEvidence toEvidence(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new PolicyEvidence(
                (String) metadata.get("policy_id"),
                document.getText(),
                (String) metadata.get("effective_from"),
                (String) metadata.get("effective_to"));
    }

    private String escapeFilterValue(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    public record PolicyEvidence(String policyId, String quote, String effectiveFrom, String effectiveTo) {
    }
}
