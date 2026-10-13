package com.MLab.assessment.service;

import com.MLab.assessment.model.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
public class BatchService {
    private final PythonServiceClient pythonServiceClient;

    public BatchService(PythonServiceClient pythonServiceClient) {
        this.pythonServiceClient = pythonServiceClient;
    }

    public BatchResponse process(
            CallerContext caller,
            BatchManifest manifest,
            List<MultipartFile> files
    ) {
        RequestValidator.requireValidManifest(manifest, files);
        RequestValidator.requireValidDate(manifest.as_of());

        Map<String, MultipartFile> filesByName = new HashMap<>();
        for (MultipartFile file : files) {
            filesByName.put(file.getOriginalFilename(), file);
        }

        Map<String, String> firstDocumentByHash = new HashMap<>();
        List<BatchItemResult> results = new ArrayList<>();
        int completed = 0;
        for (BatchDocument entry : manifest.documents()) {
            MultipartFile file = filesByName.get(entry.filename());
            BatchItemResult item;
            String duplicateOf = null;
            try {
                byte[] bytes = file.getBytes();
                String hash = sha256(bytes);
                duplicateOf = firstDocumentByHash.putIfAbsent(hash, entry.document_id());
                item = pythonServiceClient.processDocument(
                        caller, manifest.as_of(), manifest.batch_id(), entry.document_id(), entry.filename(), bytes
                );
            } catch (IOException exception) {
                item = failedItem("UNREADABLE_FILE", "The uploaded file could not be read.");
            } catch (PolicyServiceException exception) {
                item = failedItem(exception.code(), exception.getMessage());
            }
            if ("COMPLETED".equals(item.processing_status())) {
                completed++;
            }
            results.add(new BatchItemResult(
                    entry.document_id(), item.processing_status(), item.extracted(), item.field_evidence(),
                    item.policy(), true, item.issues(), duplicateOf, item.error()
            ));
        }
        return new BatchResponse(
                manifest.batch_id(),
                new BatchSummary(results.size(), completed, results.size() - completed),
                List.copyOf(results)
        );
    }

    private static BatchItemResult failedItem(String code, String message) {
        return new BatchItemResult(
                null,
                "FAILED",
                new ExtractedFields(null, null, null, null),
                Map.of(),
                null,
                true,
                List.of("Item processing failed; human review is required."),
                null,
                new ItemError(code, message)
        );
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", exception);
        }
    }

}
