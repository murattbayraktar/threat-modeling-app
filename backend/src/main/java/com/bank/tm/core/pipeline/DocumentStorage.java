package com.bank.tm.core.pipeline;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Yüklenen dosyaları dosya sisteminde saklar: {root}/{reviewId}/{docKey}-{güvenli ad}. */
public class DocumentStorage {

    private final Path root;

    public DocumentStorage(Path root) {
        this.root = root;
    }

    public String save(String reviewId, String docKey, String fileName, byte[] content) {
        try {
            Path dir = root.resolve(reviewId);
            Files.createDirectories(dir);
            String safe = fileName.replaceAll("[^\\p{L}\\p{N}._-]", "_");
            Path file = dir.resolve(docKey + "-" + safe);
            Files.write(file, content);
            return root.relativize(file).toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public byte[] read(String relativePath) {
        try {
            Path p = root.resolve(relativePath).normalize();
            if (!p.startsWith(root.normalize())) throw new IllegalArgumentException("Geçersiz yol");
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
