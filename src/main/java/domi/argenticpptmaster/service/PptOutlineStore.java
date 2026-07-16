package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import domi.argenticpptmaster.domain.PptOutline;
import domi.argenticpptmaster.domain.PptOutlineVersionDiff;
import domi.argenticpptmaster.domain.PptOutlineVersionSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 在项目工作区原子维护 outline.json。 */
public final class PptOutlineStore {
    private static final String FILE_NAME = "outline.json";
    private static final String HISTORY_DIR = ".outline-history";
    private static final String METADATA_FILE = "metadata.json";
    private static final Object WRITE_LOCK = new Object();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Path path(Path projectPath) {
        return projectPath.resolve(FILE_NAME);
    }

    public Path historyPath(Path projectPath, int version) {
        return projectPath.resolve(HISTORY_DIR).resolve("v" + version + ".json");
    }

    public Path metadataPath(Path projectPath) {
        return projectPath.resolve(HISTORY_DIR).resolve(METADATA_FILE);
    }

    public synchronized Optional<PptOutlineVersionSnapshot> snapshot(Path projectPath, int version) {
        Path target = historyPath(projectPath, version);
        if (!Files.isRegularFile(target)) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(target.toFile(), PptOutlineVersionSnapshot.class));
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException("outline snapshot is invalid", ex);
        }
    }

    public synchronized PptOutline read(Path projectPath) {
        try {
            synchronized (WRITE_LOCK) {
                PptOutline outline = objectMapper.readValue(path(projectPath).toFile(), PptOutline.class);
                outline.validate();
                ensureInitialSnapshot(projectPath, outline);
                validateHistory(projectPath, outline);
                return outline;
            }
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException("outline.json is missing or invalid", ex);
        }
    }

    public void write(Path projectPath, PptOutline outline) {
        synchronized (WRITE_LOCK) {
            outline.validate();
            try {
            Files.createDirectories(projectPath);
            PptOutline previous = Files.isRegularFile(path(projectPath))
                    ? objectMapper.readValue(path(projectPath).toFile(), PptOutline.class) : null;
            PptOutlineVersionDiff diff = previous == null || previous.version() == outline.version()
                    ? null : PptOutlineVersionDiff.between(previous, outline);
            PptOutlineVersionSnapshot snapshot = new PptOutlineVersionSnapshot(
                    outline.version(), previous == null ? null : previous.version(), outline.locked(), outline, diff,
                    java.time.Instant.now().toString());
            writeSnapshot(projectPath, snapshot);
            Path target = path(projectPath);
            Path temporary = Files.createTempFile(projectPath, "outline-", ".json.tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), outline);
            moveAtomically(temporary, target);
            writeMetadata(projectPath, outline, previous);
            } catch (IOException ex) {
                throw new IllegalStateException("failed to persist outline.json", ex);
            }
        }
    }

    private void ensureInitialSnapshot(Path projectPath, PptOutline outline) throws IOException {
        Path snapshotPath = historyPath(projectPath, outline.version());
        if (!Files.isRegularFile(snapshotPath)) {
            writeSnapshot(projectPath, PptOutlineVersionSnapshot.initial(outline));
            writeMetadata(projectPath, outline, null);
        }
    }

    private void writeSnapshot(Path projectPath, PptOutlineVersionSnapshot snapshot) throws IOException {
        Path history = projectPath.resolve(HISTORY_DIR);
        Files.createDirectories(history);
        Path target = historyPath(projectPath, snapshot.version());
        if (Files.isRegularFile(target)) {
            PptOutlineVersionSnapshot existing = objectMapper.readValue(target.toFile(), PptOutlineVersionSnapshot.class);
            if (!existing.outline().equals(snapshot.outline()) && !(existing.outline().locked() != snapshot.outline().locked()
                    && existing.outline().slides().equals(snapshot.outline().slides()))) {
                throw new IllegalStateException("outline version snapshot already exists");
            }
            return;
        }
        Path temporary = Files.createTempFile(history, "outline-version-", ".json.tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), snapshot);
        moveAtomically(temporary, target);
    }

    private void writeMetadata(Path projectPath, PptOutline outline, PptOutline previous) throws IOException {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("currentVersion", outline.version());
        Integer previousLockedVersion = null;
        Path metadataPath = metadataPath(projectPath);
        if (Files.isRegularFile(metadataPath)) {
            Map<?, ?> existing = objectMapper.readValue(metadataPath.toFile(), Map.class);
            if (existing.get("lockedVersion") instanceof Number number) {
                previousLockedVersion = number.intValue();
            }
        }
        metadata.put("lockedVersion", outline.locked() ? Integer.valueOf(outline.version()) : previousLockedVersion);
        metadata.put("parentVersion", previous == null ? null : previous.version());
        Path history = projectPath.resolve(HISTORY_DIR);
        Files.createDirectories(history);
        Path temporary = Files.createTempFile(history, "outline-metadata-", ".json.tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), metadata);
        moveAtomically(temporary, metadataPath(projectPath));
    }

    private void validateHistory(Path projectPath, PptOutline current) throws IOException {
        PptOutlineVersionSnapshot snapshot = objectMapper.readValue(
                historyPath(projectPath, current.version()).toFile(), PptOutlineVersionSnapshot.class);
        if (snapshot.version() != current.version() || !snapshot.outline().slides().equals(current.slides())) {
            throw new IllegalStateException("outline history does not match current outline");
        }
        if (Files.isRegularFile(metadataPath(projectPath))) {
            Map<?, ?> metadata = objectMapper.readValue(metadataPath(projectPath).toFile(), Map.class);
            Object currentVersion = metadata.get("currentVersion");
            if (!(currentVersion instanceof Number number) || number.intValue() != current.version()) {
                throw new IllegalStateException("outline metadata does not match current outline");
            }
        }
    }

    private void moveAtomically(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.NoSuchFileException ex) {
            if (!Files.exists(target)) {
                throw ex;
            }
        }
    }
}
