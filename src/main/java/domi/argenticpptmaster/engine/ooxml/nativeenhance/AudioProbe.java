// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.nativeenhance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Probe audio duration via ffprobe (same contract as Python {@code probe_audio_duration}). */
public final class AudioProbe {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AudioProbe() {}

    public static Optional<Double> probeDurationSeconds(Path audioPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffprobe",
                    "-v",
                    "error",
                    "-show_entries",
                    "format=duration",
                    "-of",
                    "json",
                    audioPath.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder stdout = new StringBuilder();
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdout.append(line);
                }
            }
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return Optional.empty();
            }
            JsonNode data = MAPPER.readTree(stdout.toString());
            double duration = data.path("format").path("duration").asDouble(0);
            return duration > 0 ? Optional.of(duration) : Optional.empty();
        } catch (Exception ex) {
            return Optional.empty();
        }
    }
}
