package com.chatapp.exception;

public class PasswordUpgradeRequiredException extends RuntimeException {
    public PasswordUpgradeRequiredException(String message) {
        super(message);
    }
}
