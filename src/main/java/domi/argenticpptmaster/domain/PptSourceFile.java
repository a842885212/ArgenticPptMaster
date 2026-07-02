package domi.argenticpptmaster.domain;

import java.nio.file.Path;

public record PptSourceFile(String originalName, String contentType, long size, Path storedPath) {
}
