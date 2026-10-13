package com.MLab.assessment;

import com.MLab.assessment.model.*;
import com.MLab.assessment.service.PythonServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class EmployeeSupportApiTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PythonServiceClient pythonServiceClient;

    @BeforeEach
    void resetClient() {
        reset(pythonServiceClient);
    }

    @Test
    void answerUsesKnownCallerAndReturnsPolicyResult() throws Exception {
        when(pythonServiceClient.answer(any(), eq("2026-09-21"), eq("What is my certification limit?")))
                .thenReturn(new AnswerResponse(
                        "ANSWERED",
                        "The annual certification reimbursement limit for employees is INR 25000.",
                        List.of(new Citation("atlas-cert-current",
                                "The annual certification reimbursement limit for employees is INR 25000."))
                ));

        mockMvc.perform(post("/answer")
                        .header("X-Caller-Id", "atlas-employee-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"What is my certification limit?","as_of":"2026-09-21"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.citations[0].chunk_id").value("atlas-cert-current"));

        verify(pythonServiceClient).answer(
                new CallerContext("Atlas", "employee"), "2026-09-21", "What is my certification limit?"
        );
    }

    @Test
    void rejectsUnknownCallerAndInvalidDateBeforeCallingPython() throws Exception {
        mockMvc.perform(post("/answer")
                        .header("X-Caller-Id", "unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"A policy question","as_of":"2026-09-21"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_CALLER"));

        mockMvc.perform(post("/answer")
                        .header("X-Caller-Id", "atlas-employee-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"A policy question","as_of":"2026-02-30"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AS_OF"));

        verifyNoInteractions(pythonServiceClient);
    }

    @Test
    void batchPreservesOrderAndReportsExactByteDuplicates() throws Exception {
        when(pythonServiceClient.processDocument(
                any(), eq("2026-09-21"), eq("batch-01"), anyString(), anyString(), any(byte[].class)
        )).thenAnswer(invocation -> completedItem());
        byte[] sameContents = "Reference: CERT-101\nCertification reimbursement INR 18000."
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MockMultipartFile metadata = new MockMultipartFile(
                "metadata", "metadata.json", "application/json", """
                {"batch_id":"batch-01","as_of":"2026-09-21","documents":[
                  {"document_id":"request-01","filename":"request-01.txt"},
                  {"document_id":"request-02","filename":"request-02.txt"}]}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/batches")
                        .file(metadata)
                        .file(new MockMultipartFile("files", "request-01.txt", "text/plain", sameContents))
                        .file(new MockMultipartFile("files", "request-02.txt", "text/plain", sameContents))
                        .header("X-Caller-Id", "atlas-employee-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.total").value(2))
                .andExpect(jsonPath("$.summary.completed").value(2))
                .andExpect(jsonPath("$.results[0].document_id").value("request-01"))
                .andExpect(jsonPath("$.results[1].duplicate_of").value("request-01"));
    }

    @Test
    void batchRejectsMissingUploadedFileParts() throws Exception {
        MockMultipartFile metadata = new MockMultipartFile(
                "metadata", "metadata.json", "application/json", """
                {"batch_id":"batch-01","as_of":"2026-09-21","documents":[
                  {"document_id":"request-01","filename":"request-01.txt"},
                  {"document_id":"request-02","filename":"request-02.txt"}]}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        mockMvc.perform(multipart("/batches")
                        .file(metadata)
                        .file(new MockMultipartFile("files", "request-01.txt", "text/plain", new byte[]{1}))
                        .header("X-Caller-Id", "atlas-employee-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_PART_MISMATCH"));
    }

    @Test
    void batchKeepsFailedItemAndContinuesProcessingLaterFiles() throws Exception {
        when(pythonServiceClient.processDocument(
                any(), eq("2026-09-21"), eq("batch-02"), anyString(), anyString(), any(byte[].class)
        )).thenAnswer(invocation -> {
            String documentId = invocation.getArgument(3);
            if ("request-02".equals(documentId)) {
                return new BatchItemResult(
                        null, "FAILED", new ExtractedFields(null, null, null, null), Map.of(), null,
                        true, List.of("Item processing failed; human review is required."), null,
                        new ItemError("EMPTY_DOCUMENT", "The uploaded document is empty.")
                );
            }
            return completedItem();
        });
        MockMultipartFile metadata = new MockMultipartFile(
                "metadata", "metadata.json", "application/json", """
                {"batch_id":"batch-02","as_of":"2026-09-21","documents":[
                  {"document_id":"request-01","filename":"request-01.txt"},
                  {"document_id":"request-02","filename":"request-02.txt"},
                  {"document_id":"request-03","filename":"request-03.txt"}]}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/batches")
                        .file(metadata)
                        .file(new MockMultipartFile("files", "request-01.txt", "text/plain", new byte[]{1}))
                        .file(new MockMultipartFile("files", "request-02.txt", "text/plain", new byte[0]))
                        .file(new MockMultipartFile("files", "request-03.txt", "text/plain", new byte[]{3}))
                        .header("X-Caller-Id", "atlas-employee-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.total").value(3))
                .andExpect(jsonPath("$.summary.completed").value(2))
                .andExpect(jsonPath("$.summary.failed").value(1))
                .andExpect(jsonPath("$.results[1].processing_status").value("FAILED"))
                .andExpect(jsonPath("$.results[1].error.code").value("EMPTY_DOCUMENT"))
                .andExpect(jsonPath("$.results[2].processing_status").value("COMPLETED"));
    }

    private static BatchItemResult completedItem() {
        return new BatchItemResult(
                null,
                "COMPLETED",
                new ExtractedFields("certification", new BigDecimal("18000"), "INR", "CERT-101"),
                Map.of("amount", new FieldEvidence("SUPPORTED", List.of("INR 18000"))),
                new AnswerResponse("ANSWERED", "Supported policy text.", List.of()),
                true,
                List.of("Human review is required."),
                null,
                null
        );
    }
}
