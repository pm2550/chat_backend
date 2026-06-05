package com.chatapp.exception;

public class ClientTooOldException extends RuntimeException {
    public ClientTooOldException(String message) {
        super(message);
    }
}
