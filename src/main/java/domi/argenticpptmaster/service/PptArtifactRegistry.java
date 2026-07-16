package domi.argenticpptmaster.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import domi.argenticpptmaster.domain.PptArtifactRecord;
import domi.argenticpptmaster.domain.PptArtifactStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 在项目工作区登记下游产物来源版本，并集中执行失效/版本门禁。 */
public final class PptArtifactRegistry {
    private static final String FILE_NAME = "artifacts.json";
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Path path(Path projectPath) {
        return projectPath.resolve(".outline-history").resolve(FILE_NAME);
    }

    public synchronized void register(Path projectPath, String artifactPath, int outlineVersion) {
        Map<String, PptArtifactRecord> records = read(projectPath);
        records.put(artifactPath, new PptArtifactRecord(artifactPath, outlineVersion, PptArtifactStatus.VALID));
        write(projectPath, records);
    }

    public synchronized void markStale(Path projectPath, int outlineVersion) {
        Map<String, PptArtifactRecord> records = read(projectPath);
        records.replaceAll((path, record) -> record.outlineVersion() == outlineVersion ? record.stale() : record);
        write(projectPath, records);
    }

    public synchronized boolean isUsable(Path projectPath, String artifactPath, int outlineVersion) {
        PptArtifactRecord record = read(projectPath).get(artifactPath);
        return record != null && record.outlineVersion() == outlineVersion && record.status() == PptArtifactStatus.VALID;
    }

    public synchronized List<PptArtifactRecord> affected(Path projectPath, int outlineVersion) {
        return read(projectPath).values().stream()
                .filter(record -> record.outlineVersion() == outlineVersion)
                .toList();
    }

    public synchronized boolean hasStale(Path projectPath, int outlineVersion) {
        return read(projectPath).values().stream()
                .anyMatch(record -> record.outlineVersion() == outlineVersion
                        && record.status() == PptArtifactStatus.STALE);
    }

    public synchronized boolean hasAnyStale(Path projectPath) {
        return read(projectPath).values().stream().anyMatch(record -> record.status() == PptArtifactStatus.STALE);
    }

    private Map<String, PptArtifactRecord> read(Path projectPath) {
        Path target = path(projectPath);
        if (!Files.isRegularFile(target)) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(objectMapper.readValue(target.toFile(),
                    new TypeReference<Map<String, PptArtifactRecord>>() {}));
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException("artifact registry is invalid", ex);
        }
    }

    private void write(Path projectPath, Map<String, PptArtifactRecord> records) {
        try {
            Files.createDirectories(path(projectPath).getParent());
            Path temporary = Files.createTempFile(path(projectPath).getParent(), "artifacts-", ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), records);
            try {
                Files.move(temporary, path(projectPath), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temporary, path(projectPath), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("failed to persist artifact registry", ex);
        }
    }
}
