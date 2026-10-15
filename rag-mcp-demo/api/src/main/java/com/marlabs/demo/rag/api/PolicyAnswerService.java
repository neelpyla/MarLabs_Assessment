package com.marlabs.demo.rag.api;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class PolicyAnswerService {

    private static final TypeReference<List<AnswerResponse.Citation>> CITATIONS_TYPE =
            new TypeReference<>() { };

    private final McpSyncClient mcpClient;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public PolicyAnswerService(List<McpSyncClient> mcpClients, ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        if (mcpClients.size() != 1) {
            throw new IllegalStateException("Expected exactly one MCP client, but found " + mcpClients.size());
        }
        this.mcpClient = mcpClients.get(0);
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    public AnswerResponse answer(CallerDirectory.Caller caller, AnswerRequest request) {
        CallToolResult toolResult = mcpClient.callTool(new CallToolRequest(
                "search_policies",
                Map.of(
                        "query", request.question(),
                        "tenant", caller.tenant(),
                        "role", caller.role(),
                        "asOf", request.asOf().toString())));
        if (toolResult.isError()) {
            throw new IllegalStateException("MCP policy search tool returned an error");
        }

        List<AnswerResponse.Citation> citations = readEvidence(toolResult);
        if (citations.isEmpty()) {
            return new AnswerResponse("INSUFFICIENT_EVIDENCE", null, List.of());
        }

        String evidence = citations.stream()
                .map(citation -> "[" + citation.policyId() + "] " + citation.quote())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElseThrow();
        String answer = chatClient.prompt()
                .system("""
                        Answer the user's policy question using only the supplied policy evidence.
                        If it does not answer the question, say that the evidence is insufficient.
                        Do not invent policy details or citations. Keep the answer concise.
                        """)
                .user(user -> user.text("""
                        Question: {question}

                        Effective date: {asOf}

                        Retrieved evidence:
                        {evidence}
                        """)
                        .param("question", request.question())
                        .param("asOf", request.asOf())
                        .param("evidence", evidence))
                .call()
                .content();
        return new AnswerResponse("ANSWERED", answer, citations);
    }

    private List<AnswerResponse.Citation> readEvidence(CallToolResult result) {
        try {
            return result.content().stream()
                    .filter(TextContent.class::isInstance)
                    .map(TextContent.class::cast)
                    .map(TextContent::text)
                    .map(this::parseCitation)
                    .flatMap(List::stream)
                    .toList();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Could not read policy evidence returned by MCP", exception);
        }
    }

    private List<AnswerResponse.Citation> parseCitation(String json) {
        try {
            return objectMapper.readValue(json, CITATIONS_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("MCP policy search returned an invalid evidence payload", exception);
        }
    }
}
