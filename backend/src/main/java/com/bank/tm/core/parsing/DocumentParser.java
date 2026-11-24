package com.bank.tm.core.parsing;

/** Dosya türüne özgü ayrıştırıcı. Yeni format = yeni uygulama + DocumentParsingService'e kayıt. */
public interface DocumentParser {

    String name();

    boolean supports(String fileName, String contentType);

    ParsedDocument parse(String documentKey, String fileName, byte[] content) throws Exception;
}
