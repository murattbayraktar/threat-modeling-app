package com.bank.tm.app.web;

/** Yetkisiz işlem (HTTP 403). */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
