package com.marlabs.demo.policy.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

class PolicySeederTest {

    @Test
    void seedsUuidDocumentIdsAndPreservesPolicyIdsInMetadata() {
        VectorStore vectorStore = mock(VectorStore.class);
        List<List<Document>> seededBatches = new ArrayList<>();
        doAnswer(invocation -> {
            seededBatches.add(invocation.getArgument(0));
            return null;
        }).when(vectorStore).add(anyList());

        new PolicySeeder(vectorStore).run(null);

        verify(vectorStore).add(anyList());
        assertEquals(1, seededBatches.size());
        List<Document> policies = seededBatches.get(0);
        assertEquals(Set.of(
                "atlas-cert-2026",
                "atlas-cert-contractor-2026",
                "boreal-cert-2026",
                "atlas-travel-2026"), policies.stream()
                .map(document -> (String) document.getMetadata().get("policy_id"))
                .collect(Collectors.toSet()));
        policies.forEach(document -> assertDoesNotThrow(() -> UUID.fromString(document.getId())));
    }
}
