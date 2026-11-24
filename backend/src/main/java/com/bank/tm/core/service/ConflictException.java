package com.bank.tm.core.service;

/** İstek, incelemenin mevcut durumunda yapılamaz (ör. onaylanmış model düzenlenmek istenirse). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
