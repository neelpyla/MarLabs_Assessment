package com.MLab.assessment.service;

import com.MLab.assessment.model.AnswerResponse;
import com.MLab.assessment.model.CallerContext;
import com.MLab.assessment.model.BatchItemResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;

@Component
public class PythonServiceClient {
    private final RestClient restClient;

    public PythonServiceClient(
            RestClient.Builder builder,
            @Value("${python.service.base-url}") String baseUrl,
            @Value("${python.service.connect-timeout}") Duration connectTimeout,
            @Value("${python.service.read-timeout}") Duration readTimeout
    ) {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        org.springframework.http.client.JdkClientHttpRequestFactory requestFactory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    public AnswerResponse answer(CallerContext caller, String asOf, String question) {
        Map<String, Object> request = Map.of(
                "tenant", caller.tenant(),
                "role", caller.role(),
                "as_of", asOf,
                "question", question
        );
        try {
            AnswerResponse response = restClient.post()
                    .uri("/answer")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(AnswerResponse.class);
            if (!isValidAnswer(response)) {
                throw new IllegalStateException("The policy service returned an invalid answer response.");
            }
            return response;
        } catch (ResourceAccessException exception) {
            throw new PolicyServiceException(504, "DEPENDENCY_TIMEOUT",
                    "The policy service did not respond within the configured timeout.", exception);
        } catch (PolicyServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PolicyServiceException(502, "DEPENDENCY_FAILURE",
                    "The policy service could not complete the request.", exception);
        }
    }

    public BatchItemResult processDocument(
            CallerContext caller,
            String asOf,
            String batchId,
            String documentId,
            String filename,
            byte[] contents
    ) {
        Map<String, Object> request = Map.of(
                "tenant", caller.tenant(),
                "role", caller.role(),
                "as_of", asOf,
                "batch_id", batchId,
                "document_id", documentId,
                "filename", filename,
                "content_base64", Base64.getEncoder().encodeToString(contents)
        );
        try {
            BatchItemResult response = restClient.post()
                    .uri("/process")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(BatchItemResult.class);
            if (!isValidItem(response)) {
                throw new IllegalStateException("The policy service returned an invalid item response.");
            }
            return response;
        } catch (RuntimeException exception) {
            throw new PolicyServiceException(
                    exception instanceof ResourceAccessException ? 504 : 502,
                    exception instanceof ResourceAccessException ? "DEPENDENCY_TIMEOUT" : "DEPENDENCY_FAILURE",
                    "The policy service could not process this document.", exception
            );
        }
    }

    private static boolean isValidAnswer(AnswerResponse response) {
        if (response == null || response.citations() == null) {
            return false;
        }
        boolean hasCitations = !response.citations().isEmpty();
        boolean validCitations = response.citations().stream().allMatch(citation ->
                citation != null && citation.chunk_id() != null && !citation.chunk_id().isBlank()
                        && citation.quote() != null && !citation.quote().isBlank());
        return validCitations && switch (response.status() == null ? "" : response.status()) {
            case "ANSWERED" -> response.answer() != null && !response.answer().isBlank() && hasCitations;
            case "CONFLICT" -> response.answer() == null && response.citations().size() >= 2;
            case "INSUFFICIENT_EVIDENCE" -> response.answer() == null && !hasCitations;
            default -> false;
        };
    }

    private static boolean isValidItem(BatchItemResult item) {
        if (item == null || !item.review_required() || item.issues() == null) {
            return false;
        }
        if ("COMPLETED".equals(item.processing_status())) {
            return item.extracted() != null && item.field_evidence() != null && item.error() == null;
        }
        return "FAILED".equals(item.processing_status())
                && item.extracted() != null
                && item.field_evidence() != null
                && item.policy() == null
                && item.error() != null;
    }
}
