package domi.argenticpptmaster.engine.file;

import domi.argenticpptmaster.config.PptAudioFishProperties;
import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.NotesAudioGateway;
import domi.argenticpptmaster.engine.file.fishaudio.FishAudioClient;
import domi.argenticpptmaster.engine.file.fishaudio.FishAudioException;
import domi.argenticpptmaster.engine.file.fishaudio.FishVoicePresets;
import domi.argenticpptmaster.engine.file.fishaudio.SpokenText;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 服务内 Fish Audio 旁白网关：{@code notes/*.md} → {@code audio/<stem>.mp3}。
 */
public final class JavaFishNotesAudioGateway implements NotesAudioGateway {

    private static final int MAX_ATTEMPTS_PER_MODEL = 2;

    private final PptAudioFishProperties properties;
    private final FishAudioClient client;

    public JavaFishNotesAudioGateway(PptAudioFishProperties properties, FishAudioClient client) {
        if (properties == null || client == null) {
            throw new IllegalArgumentException("properties and client are required");
        }
        this.properties = properties;
        this.client = client;
    }

    @Override
    public EngineResult synthesize(Path project, String voice, Duration timeout) {
        if (!properties.hasApiKey()) {
            return new EngineResult(1, PptAudioFishProperties.MISSING_API_KEY_MESSAGE, false);
        }
        String resolvedVoice;
        String referenceId;
        try {
            resolvedVoice = voice == null || voice.isBlank() ? FishVoicePresets.DEFAULT_VOICE : voice.trim();
            referenceId = FishVoicePresets.requireReferenceId(resolvedVoice);
        } catch (IllegalArgumentException ex) {
            return new EngineResult(1, ex.getMessage(), false);
        }
        if (project == null) {
            return new EngineResult(1, "project path is required", false);
        }
        Path notesDir = project.resolve("notes");
        Path audioDir = project.resolve("audio");
        List<Path> noteFiles;
        try {
            noteFiles = listNoteFiles(notesDir);
        } catch (IOException ex) {
            return new EngineResult(1, "cannot read notes directory: " + ex.getMessage(), false);
        }
        if (noteFiles.isEmpty()) {
            return new EngineResult(2, "error: no per-slide notes found in " + notesDir, false);
        }
        try {
            Files.createDirectories(audioDir);
        } catch (IOException ex) {
            return new EngineResult(1, "cannot create audio directory: " + ex.getMessage(), false);
        }

        List<PageJob> jobs = new ArrayList<>();
        for (Path notePath : noteFiles) {
            String text;
            try {
                text = SpokenText.fromMarkdown(Files.readString(notePath));
            } catch (IOException ex) {
                return new EngineResult(1, "failed to read " + notePath.getFileName() + ": " + ex.getMessage(), false);
            }
            if (text.isBlank()) {
                continue;
            }
            jobs.add(new PageJob(notePath, audioDir.resolve(stem(notePath) + ".mp3"), text));
        }
        if (jobs.isEmpty()) {
            return new EngineResult(0,
                    "[Done] Generated 0/" + noteFiles.size() + " audio file(s): " + audioDir, false);
        }

        Duration bound = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofMinutes(10)
                : timeout;
        int concurrency = Math.min(properties.effectiveConcurrency(), jobs.size());
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        AtomicInteger ok = new AtomicInteger();
        List<String> lines = new ArrayList<>();
        lines.add("[Fish] " + jobs.size() + " page(s), concurrency=" + concurrency
                + ", voice=" + resolvedVoice + ", model=" + properties.model());
        boolean timedOut = false;
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (PageJob job : jobs) {
                futures.add(pool.submit(() -> synthesizePage(job, referenceId)));
            }
            pool.shutdown();
            timedOut = !pool.awaitTermination(bound.toMillis(), TimeUnit.MILLISECONDS);
            if (timedOut) {
                pool.shutdownNow();
            }
            for (Future<String> future : futures) {
                if (timedOut && !future.isDone()) {
                    continue;
                }
                try {
                    String line = future.get();
                    synchronized (lines) {
                        lines.add(line);
                    }
                    ok.incrementAndGet();
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                    lines.add("error: " + message);
                    return new EngineResult(1, String.join("\n", lines), timedOut);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return new EngineResult(1, "fish audio batch interrupted", true);
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
            return new EngineResult(1, "fish audio batch interrupted", true);
        }
        if (timedOut) {
            lines.add("error: audio synthesis timed out");
            return new EngineResult(1, String.join("\n", lines), true);
        }
        lines.add("[Done] Generated " + ok.get() + "/" + noteFiles.size() + " audio file(s): " + audioDir);
        return new EngineResult(0, String.join("\n", lines), false);
    }

    private String synthesizePage(PageJob job, String referenceId) throws IOException {
        String primary = properties.model();
        String fallback = properties.effectiveFallbackModel();
        FishAudioException last = null;
        last = attemptModel(job, referenceId, primary, last);
        if (last == null) {
            return "[OK] " + job.output().getFileName();
        }
        if (fallback != null && !fallback.equals(primary) && last.recoverable()) {
            last = attemptModel(job, referenceId, fallback, last);
            if (last == null) {
                return "[OK] " + job.output().getFileName() + " (fallback-model)";
            }
        }
        throw last;
    }

    private FishAudioException attemptModel(
            PageJob job, String referenceId, String model, FishAudioException previous) throws IOException {
        FishAudioException last = previous;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS_PER_MODEL; attempt++) {
            try {
                byte[] audio = client.synthesize(job.text(), referenceId, model);
                Files.write(job.output(), audio);
                return null;
            } catch (FishAudioException ex) {
                last = ex;
                if (!ex.recoverable() || attempt == MAX_ATTEMPTS_PER_MODEL) {
                    return ex;
                }
            }
        }
        return last;
    }

    private static List<Path> listNoteFiles(Path notesDir) throws IOException {
        if (!Files.isDirectory(notesDir)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(notesDir, "*.md")) {
            for (Path path : stream) {
                if (!"total.md".equalsIgnoreCase(path.getFileName().toString())) {
                    files.add(path);
                }
            }
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return files;
    }

    private static String stem(Path notePath) {
        String name = notePath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private record PageJob(Path note, Path output, String text) {}
}
