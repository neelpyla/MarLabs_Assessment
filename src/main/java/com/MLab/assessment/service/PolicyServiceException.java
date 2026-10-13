package com.MLab.assessment.service;

public class PolicyServiceException extends RuntimeException {
    private final int httpStatus;
    private final String code;

    public PolicyServiceException(int httpStatus, String code, String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }
}
