package com.chatapp.exception;

import org.springframework.http.HttpStatus;

public class PointsException extends RuntimeException {
    private final HttpStatus status;

    public PointsException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
