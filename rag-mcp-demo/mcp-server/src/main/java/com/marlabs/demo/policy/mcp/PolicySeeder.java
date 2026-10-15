package com.marlabs.demo.policy.mcp;

import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class PolicySeeder implements ApplicationRunner {

    private final VectorStore vectorStore;

    public PolicySeeder(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Document> policies = List.of(
                policy("atlas-cert-2026", "atlas", "employee", "certification",
                        LocalDate.parse("2026-01-01"), LocalDate.parse("2027-01-01"),
                        "Atlas employees may be reimbursed up to INR 25,000 per calendar year for approved job-related certifications. Submit proof of payment and course completion."),
                policy("atlas-cert-contractor-2026", "atlas", "contractor", "certification",
                        LocalDate.parse("2026-01-01"), LocalDate.parse("2027-01-01"),
                        "Atlas contractors are not eligible for certification reimbursement under this policy."),
                policy("boreal-cert-2026", "boreal", "employee", "certification",
                        LocalDate.parse("2026-01-01"), LocalDate.parse("2027-01-01"),
                        "Boreal employees may be reimbursed up to USD 1,000 per calendar year for approved job-related certifications."),
                policy("atlas-travel-2026", "atlas", "employee", "travel",
                        LocalDate.parse("2026-01-01"), LocalDate.parse("2027-01-01"),
                        "Atlas employees must submit business travel expenses within 30 days of returning from a trip."));
        vectorStore.add(policies);
    }

    private Document policy(
            String id,
            String tenant,
            String role,
            String topic,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String text) {
        String documentId = UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString();
        return new Document(documentId, text, Map.of(
                "policy_id", id,
                "tenant", tenant,
                "role", role,
                "topic", topic,
                "effective_from", effectiveFrom.toString(),
                "effective_to", effectiveTo.toString()));
    }
}
