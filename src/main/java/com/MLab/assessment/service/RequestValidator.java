package com.MLab.assessment.service;

import com.MLab.assessment.model.BatchDocument;
import com.MLab.assessment.model.BatchManifest;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class RequestValidator {
    private static final int MAX_DOCUMENTS = 20;
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private RequestValidator() {
    }

    public static void requireValidDate(String value) {
        if (value == null || !ISO_DATE.matcher(value).matches()) {
            throw new InvalidRequestException("INVALID_AS_OF", "as_of must be a valid date in YYYY-MM-DD format.");
        }
        try {
            LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new InvalidRequestException("INVALID_AS_OF", "as_of must be a valid date in YYYY-MM-DD format.");
        }
    }

    public static void requireValidManifest(BatchManifest manifest, List<MultipartFile> files) {
        if (manifest == null || isBlank(manifest.batch_id()) || manifest.documents() == null
                || manifest.documents().isEmpty() || manifest.documents().size() > MAX_DOCUMENTS) {
            throw new InvalidRequestException("INVALID_MANIFEST",
                    "Manifest must include a batch_id and between 1 and 20 documents.");
        }
        if (files == null) {
            throw new InvalidRequestException("FILE_PART_MISMATCH", "Uploaded files must match manifest entries.");
        }

        Set<String> documentIds = new HashSet<>();
        Set<String> filenames = new HashSet<>();
        for (BatchDocument document : manifest.documents()) {
            if (document == null || isBlank(document.document_id()) || isBlank(document.filename())
                    || !documentIds.add(document.document_id()) || !filenames.add(document.filename())) {
                throw new InvalidRequestException("INVALID_MANIFEST",
                        "Document IDs and filenames must be present and unique.");
            }
            String lowerFilename = document.filename().toLowerCase(Locale.ROOT);
            if (!lowerFilename.endsWith(".txt") && !lowerFilename.endsWith(".pdf")) {
                throw new InvalidRequestException("INVALID_MANIFEST",
                        "Each filename must identify a .txt or .pdf document.");
            }
        }

        Set<String> uploadedNames = new HashSet<>();
        for (MultipartFile file : files) {
            String filename = file.getOriginalFilename();
            if (isBlank(filename) || !uploadedNames.add(filename)) {
                throw new InvalidRequestException("FILE_PART_MISMATCH",
                        "Uploaded filenames must be present and unique.");
            }
        }
        if (files.size() != manifest.documents().size() || !uploadedNames.equals(filenames)) {
            throw new InvalidRequestException("FILE_PART_MISMATCH",
                    "Uploaded filenames must exactly match manifest entries.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
