package com.bank.tm.core.service;

import com.bank.tm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** datasets/sample-projects altındaki örnek projeler (demo ve değerlendirme için). */
public class SampleLibrary {

    public record SampleFile(String fileName, String contentType, long size) {
    }

    public record Sample(String name, JsonNode intake, List<SampleFile> files, boolean hasEvaluation) {
    }

    private final Path samplesDir;
    private final Path evaluationDir;

    public SampleLibrary(Path samplesDir, Path evaluationDir) {
        this.samplesDir = samplesDir;
        this.evaluationDir = evaluationDir;
    }

    public List<Sample> list() {
        if (!Files.isDirectory(samplesDir)) return List.of();
        try (Stream<Path> dirs = Files.list(samplesDir)) {
            return dirs.filter(Files::isDirectory).filter(p -> !p.getFileName().toString().startsWith("_"))
                    .sorted().map(p -> get(p.getFileName().toString())).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Sample get(String name) {
        Path dir = dir(name);
        try (Stream<Path> files = Files.list(dir)) {
            List<SampleFile> list = new ArrayList<>();
            for (Path f : files.filter(Files::isRegularFile).filter(f -> !f.getFileName().toString().equals("intake.json"))
                    .sorted(Comparator.comparingInt(SampleLibrary::order).thenComparing(Path::toString)).toList()) {
                list.add(new SampleFile(f.getFileName().toString(), contentType(f.getFileName().toString()), Files.size(f)));
            }
            Path intake = dir.resolve("intake.json");
            JsonNode intakeJson = Files.exists(intake) ? Json.MAPPER.readTree(Files.readString(intake)) : Json.MAPPER.createObjectNode();
            return new Sample(name, intakeJson, list, Files.exists(evaluationDir.resolve(name + ".expected.json")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public byte[] read(String name, String fileName) {
        Path p = dir(name).resolve(fileName).normalize();
        if (!p.startsWith(dir(name))) throw new IllegalArgumentException("Geçersiz dosya adı");
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public JsonNode expected(String name) {
        Path p = evaluationDir.resolve(name + ".expected.json");
        if (!Files.exists(p)) return null;
        try {
            return Json.MAPPER.readTree(Files.readString(p));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path dir(String name) {
        if (!name.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Geçersiz örnek adı");
        Path dir = samplesDir.resolve(name);
        if (!Files.isDirectory(dir)) throw new NotFoundException("Örnek proje bulunamadı: " + name);
        return dir;
    }

    /** Metin dokümanları önce, diyagramlar sonra, görseller en son. */
    private static int order(Path p) {
        String f = p.getFileName().toString().toLowerCase();
        if (f.matches(".*\\.(docx|md|txt|pdf)$")) return 0;
        if (f.matches(".*\\.(drawio|xml)$")) return 1;
        return 2;
    }

    public static String contentType(String fileName) {
        String f = fileName.toLowerCase();
        Map<String, String> m = Map.of(".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                ".pdf", "application/pdf", ".md", "text/markdown", ".txt", "text/plain", ".drawio", "application/xml",
                ".png", "image/png", ".jpg", "image/jpeg", ".jpeg", "image/jpeg");
        return m.entrySet().stream().filter(e -> f.endsWith(e.getKey())).map(Map.Entry::getValue).findFirst()
                .orElse("application/octet-stream");
    }
}
