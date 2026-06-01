package com.chatapp.exception;

import org.springframework.http.HttpStatus;

public class CodeNotFoundException extends PointsException {
    public CodeNotFoundException() {
        super(HttpStatus.NOT_FOUND, "兑换码不存在");
    }
}
