package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import domi.argenticpptmaster.domain.PptOutline;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 在项目工作区原子维护 outline.json。 */
public final class PptOutlineStore {
    private static final String FILE_NAME = "outline.json";
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Path path(Path projectPath) {
        return projectPath.resolve(FILE_NAME);
    }

    public PptOutline read(Path projectPath) {
        try {
            PptOutline outline = objectMapper.readValue(path(projectPath).toFile(), PptOutline.class);
            outline.validate();
            return outline;
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException("outline.json is missing or invalid", ex);
        }
    }

    public void write(Path projectPath, PptOutline outline) {
        outline.validate();
        try {
            Files.createDirectories(projectPath);
            Path target = path(projectPath);
            Path temporary = Files.createTempFile(projectPath, "outline-", ".json.tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), outline);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("failed to persist outline.json", ex);
        }
    }
}
