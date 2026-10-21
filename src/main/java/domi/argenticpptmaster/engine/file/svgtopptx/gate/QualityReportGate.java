// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgtopptx.gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Release-export quality report gate matching pin {@code _quality_report_context} /
 * {@code _quality_gate_status}.
 */
public final class QualityReportGate {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Fingerprint(String algorithm, String digest, int fileCount, List<Map<String, String>> files) {}

    public record GateResult(String status, String detail) {
        public boolean passed() {
            return "passed".equals(status);
        }
    }

    private QualityReportGate() {}

    public static Fingerprint svgSourceFingerprint(List<Path> svgFiles) throws IOException {
        List<Path> sorted = svgFiles.stream()
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
        List<Map<String, String>> files = new ArrayList<>();
        MessageDigest aggregate = sha256();
        for (Path path : sorted) {
            String fileSha = HexFormat.of().formatHex(sha256().digest(Files.readAllBytes(path)));
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("file", path.getFileName().toString());
            entry.put("sha256", fileSha);
            files.add(entry);
            aggregate.update(path.getFileName().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            aggregate.update((byte) 0);
            aggregate.update(fileSha.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            aggregate.update((byte) '\n');
        }
        return new Fingerprint("sha256", HexFormat.of().formatHex(aggregate.digest()), files.size(), files);
    }

    public static GateResult evaluate(Path project, Fingerprint sourceFingerprint) {
        Path qualityPath = project.resolve("validation/svg_quality_report.json");
        if (!Files.isRegularFile(qualityPath)) {
            return new GateResult("not-provided", qualityPath.toString());
        }
        JsonNode quality;
        try {
            quality = MAPPER.readTree(Files.readString(qualityPath));
        } catch (IOException ex) {
            return new GateResult("unreadable", ex.getMessage());
        }
        if (!"ppt-master.svg-quality-report.v1".equals(text(quality, "schema"))) {
            return new GateResult("unsupported-schema", text(quality, "schema"));
        }
        if (!"final".equals(text(quality, "stage"))) {
            return new GateResult("non-final", text(quality, "stage"));
        }
        JsonNode blocking = quality.path("categories").path("blocking").path("count");
        if (!blocking.isInt() && !blocking.isIntegralNumber()) {
            return new GateResult("unverified", "blocking.count missing");
        }
        if (blocking.asInt() > 0) {
            return new GateResult("failed", "blocking.count=" + blocking.asInt());
        }
        JsonNode fp = quality.get("source_fingerprint");
        if (fp == null || !fp.isObject()) {
            return new GateResult("unverified", "source_fingerprint unavailable");
        }
        boolean match = "sha256".equals(text(fp, "algorithm"))
                && sourceFingerprint.digest().equals(text(fp, "digest"))
                && sourceFingerprint.fileCount() == fp.path("file_count").asInt(-1);
        if (!match) {
            return new GateResult("stale", "source fingerprint mismatch");
        }
        return new GateResult("passed", "ok");
    }

    public static List<Path> listSvgOutput(Path project) throws IOException {
        Path dir = project.resolve("svg_output");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".svg"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child == null || child.isNull() ? null : child.asText();
    }
}
