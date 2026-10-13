package com.MLab.assessment.controller;

import com.MLab.assessment.model.ApiError;
import com.MLab.assessment.service.InvalidRequestException;
import com.MLab.assessment.service.PolicyServiceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiError> handleInvalidRequest(InvalidRequestException exception) {
        return ResponseEntity.badRequest().body(new ApiError(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(PolicyServiceException.class)
    public ResponseEntity<ApiError> handleDependency(PolicyServiceException exception) {
        return ResponseEntity.status(exception.httpStatus())
                .body(new ApiError(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_REQUEST", "question and as_of are required."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "Request body is invalid."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "Request metadata is invalid."));
    }
}
