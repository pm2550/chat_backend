package com.chatapp.exception;

import org.springframework.http.HttpStatus;

public class InsufficientPointsException extends PointsException {
    public InsufficientPointsException(String message) {
        super(HttpStatus.PAYMENT_REQUIRED, message);
    }
}
