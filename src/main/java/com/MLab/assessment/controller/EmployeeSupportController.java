package com.MLab.assessment.controller;

import com.MLab.assessment.model.*;
import com.MLab.assessment.service.BatchService;
import com.MLab.assessment.service.CallerDirectory;
import com.MLab.assessment.service.PythonServiceClient;
import com.MLab.assessment.service.RequestValidator;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
public class EmployeeSupportController {
    private final CallerDirectory callerDirectory;
    private final PythonServiceClient pythonServiceClient;
    private final BatchService batchService;

    public EmployeeSupportController(
            CallerDirectory callerDirectory,
            PythonServiceClient pythonServiceClient,
            BatchService batchService
    ) {
        this.callerDirectory = callerDirectory;
        this.pythonServiceClient = pythonServiceClient;
        this.batchService = batchService;
    }

    @PostMapping(path = "/answer", consumes = MediaType.APPLICATION_JSON_VALUE)
    public AnswerResponse answer(
            @RequestHeader(value = "X-Caller-Id", required = false) String callerId,
            @Valid @RequestBody AnswerRequest request
    ) {
        CallerContext caller = callerDirectory.requireCaller(callerId);
        RequestValidator.requireValidDate(request.as_of());
        return pythonServiceClient.answer(caller, request.as_of(), request.question());
    }

    @PostMapping(path = "/batches", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BatchResponse batch(
            @RequestHeader(value = "X-Caller-Id", required = false) String callerId,
            @RequestPart("metadata") BatchManifest manifest,
            @RequestPart("files") List<MultipartFile> files
    ) {
        CallerContext caller = callerDirectory.requireCaller(callerId);
        return batchService.process(caller, manifest, files);
    }
}
