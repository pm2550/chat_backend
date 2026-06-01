package com.chatapp.exception;

import org.springframework.http.HttpStatus;

public class CodeAlreadyRedeemedException extends PointsException {
    public CodeAlreadyRedeemedException() {
        super(HttpStatus.CONFLICT, "兑换码已被使用");
    }
}
