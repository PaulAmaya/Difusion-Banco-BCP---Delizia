package com.example.defusion_bcp.service;

import org.springframework.http.HttpStatus;

public class SapServiceException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public SapServiceException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public SapServiceException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
