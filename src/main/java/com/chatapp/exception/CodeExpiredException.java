package com.chatapp.exception;

import org.springframework.http.HttpStatus;

public class CodeExpiredException extends PointsException {
    public CodeExpiredException() {
        super(HttpStatus.GONE, "兑换码已过期");
    }
}
