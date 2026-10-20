// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.nativeenhance;

import domi.argenticpptmaster.engine.ooxml.MarkdownNotes;
import domi.argenticpptmaster.engine.ooxml.NotesApplier;
import domi.argenticpptmaster.engine.ooxml.SlidePartRef;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Inspect notes/audio inputs for validate and apply (Python {@code _collect_material_readiness}). */
public final class MaterialReadiness {

    private final Map<Integer, Path> notePaths;
    private final Map<Integer, Path> audioPaths;
    private final Map<Integer, Double> audioDurations;
    private final int notesCount;
    private final int audioCount;
    private final List<Integer> missingNotes;
    private final Map<Integer, String> invalidNotes;
    private final List<Integer> missingAudio;
    private final Map<Integer, String> invalidAudio;
    private final List<String> moduleErrors;

    public MaterialReadiness(
            Map<Integer, Path> notePaths,
            Map<Integer, Path> audioPaths,
            Map<Integer, Double> audioDurations,
            List<Integer> missingNotes,
            Map<Integer, String> invalidNotes,
            List<Integer> missingAudio,
            Map<Integer, String> invalidAudio,
            List<String> moduleErrors) {
        this.notePaths = Map.copyOf(notePaths);
        this.audioPaths = Map.copyOf(audioPaths);
        this.audioDurations = Map.copyOf(audioDurations);
        this.notesCount = notePaths.size();
        this.audioCount = audioPaths.size();
        this.missingNotes = List.copyOf(missingNotes);
        this.invalidNotes = Map.copyOf(invalidNotes);
        this.missingAudio = List.copyOf(missingAudio);
        this.invalidAudio = Map.copyOf(invalidAudio);
        this.moduleErrors = List.copyOf(moduleErrors);
    }

    public boolean ready() {
        return missingNotes.isEmpty()
                && invalidNotes.isEmpty()
                && missingAudio.isEmpty()
                && invalidAudio.isEmpty()
                && moduleErrors.isEmpty();
    }

    public Map<Integer, Path> notePaths() {
        return notePaths;
    }

    public Map<Integer, Path> audioPaths() {
        return audioPaths;
    }

    public Map<Integer, Double> audioDurations() {
        return audioDurations;
    }

    public int notesCount() {
        return notesCount;
    }

    public int audioCount() {
        return audioCount;
    }

    public List<Integer> missingNotes() {
        return missingNotes;
    }

    public Map<Integer, String> invalidNotes() {
        return invalidNotes;
    }

    public List<Integer> missingAudio() {
        return missingAudio;
    }

    public Map<Integer, String> invalidAudio() {
        return invalidAudio;
    }

    public List<String> moduleErrors() {
        return moduleErrors;
    }

    public List<String> messages() {
        List<String> messages = new ArrayList<>(moduleErrors);
        if (!missingNotes.isEmpty()) {
            messages.add("missing notes for slide(s): " + joinIndices(missingNotes));
        }
        if (!invalidNotes.isEmpty()) {
            List<String> parts = new ArrayList<>();
            invalidNotes.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> parts.add("slide " + e.getKey() + ": " + e.getValue()));
            messages.add("invalid notes: " + String.join("; ", parts));
        }
        if (!missingAudio.isEmpty()) {
            messages.add("missing audio for slide(s): " + joinIndices(missingAudio));
        }
        if (!invalidAudio.isEmpty()) {
            List<String> parts = new ArrayList<>();
            invalidAudio.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> parts.add("slide " + e.getKey() + ": " + e.getValue()));
            messages.add("invalid audio: " + String.join("; ", parts));
        }
        return messages;
    }

    public static MaterialReadiness collect(
            List<SlidePartRef> slides,
            Path notesDir,
            Path audioDir,
            Set<String> modules,
            Set<String> requiredModules)
            throws IOException {
        Set<String> materialModules = requiredModules == null ? modules : requiredModules;
        boolean notesRequired = materialModules.contains("notes");
        boolean audioRequired = materialModules.contains("audio") || materialModules.contains("timings");
        boolean timingsEnabled = materialModules.contains("timings");

        Map<Integer, Path> notePaths = new LinkedHashMap<>();
        Map<Integer, Path> audioPaths = new LinkedHashMap<>();
        Map<Integer, Double> audioDurations = new LinkedHashMap<>();
        List<Integer> missingNotes = new ArrayList<>();
        Map<Integer, String> invalidNotes = new LinkedHashMap<>();
        List<Integer> missingAudio = new ArrayList<>();
        Map<Integer, String> invalidAudio = new LinkedHashMap<>();

        for (SlidePartRef slide : slides) {
            Optional<Path> note = NotesApplier.resolveNotePath(notesDir, slide.index());
            if (note.isEmpty()) {
                if (notesRequired) {
                    missingNotes.add(slide.index());
                }
            } else {
                try {
                    String plain = MarkdownNotes.toPlainText(Files.readString(note.get(), StandardCharsets.UTF_8));
                    if (!plain.isEmpty()) {
                        notePaths.put(slide.index(), note.get());
                    } else if (notesRequired) {
                        invalidNotes.put(slide.index(), note.get().getFileName() + " has no spoken text");
                    }
                } catch (IOException | RuntimeException ex) {
                    if (notesRequired) {
                        invalidNotes.put(slide.index(), "unable to read " + note.get().getFileName() + ": " + ex.getMessage());
                    }
                }
            }

            Path audio;
            try {
                audio = resolveAudioPath(audioDir, slide.index()).orElse(null);
            } catch (IllegalArgumentException ex) {
                if (audioRequired) {
                    invalidAudio.put(slide.index(), ex.getMessage());
                }
                continue;
            }
            if (audio == null) {
                if (audioRequired) {
                    missingAudio.add(slide.index());
                }
                continue;
            }
            try {
                if (!Files.isRegularFile(audio) || Files.size(audio) <= 0) {
                    throw new IllegalArgumentException(audio.getFileName() + " is not a non-empty file");
                }
            } catch (IOException | IllegalArgumentException ex) {
                if (audioRequired) {
                    invalidAudio.put(slide.index(), ex.getMessage());
                }
                continue;
            }

            if (audioRequired) {
                Optional<Double> duration = AudioProbe.probeDurationSeconds(audio);
                if (duration.isEmpty()) {
                    invalidAudio.put(slide.index(), "unable to decode " + audio.getFileName() + " with ffprobe");
                    continue;
                }
                if (timingsEnabled) {
                    audioDurations.put(slide.index(), duration.get());
                }
            }
            audioPaths.put(slide.index(), audio);
        }

        List<String> moduleErrors = new ArrayList<>();
        if (timingsEnabled && !modules.contains("audio")) {
            moduleErrors.add("timings requires the audio module");
        }
        return new MaterialReadiness(
                notePaths,
                audioPaths,
                audioDurations,
                missingNotes,
                invalidNotes,
                missingAudio,
                invalidAudio,
                moduleErrors);
    }

    public static Optional<Path> resolveAudioPath(Path audioDir, int index) {
        if (!Files.isDirectory(audioDir)) {
            return Optional.empty();
        }
        List<String> stems = List.of(
                String.format("%03d", index),
                String.format("%02d", index),
                String.valueOf(index),
                String.format("slide%03d", index),
                String.format("slide%02d", index),
                "slide" + index);
        for (String stem : stems) {
            List<Path> matches = new ArrayList<>();
            for (String ext : NativeEnhanceConstants.NARRATION_EXTENSIONS) {
                Path candidate = audioDir.resolve(stem + ext);
                if (Files.isRegularFile(candidate)) {
                    matches.add(candidate);
                }
            }
            if (matches.size() > 1) {
                String names = matches.stream().map(p -> p.getFileName().toString()).reduce((a, b) -> a + ", " + b).orElse("");
                throw new IllegalArgumentException(
                        "ambiguous audio stem '" + stem + "': " + names + "; keep exactly one supported extension");
            }
            if (!matches.isEmpty()) {
                return Optional.of(matches.get(0));
            }
        }
        return Optional.empty();
    }

    private static String joinIndices(List<Integer> indices) {
        return indices.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("");
    }
}
