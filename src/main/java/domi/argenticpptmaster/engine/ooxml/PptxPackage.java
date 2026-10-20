// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Extract and re-pack PPTX (ZIP/OPC) packages without Apache POI. */
public final class PptxPackage {

    private PptxPackage() {}

    public static void extract(Path sourcePptx, Path extractDir) throws IOException {
        Files.createDirectories(extractDir);
        try (ZipFile zip = new ZipFile(sourcePptx.toFile())) {
            var entries = zip.stream().sorted(Comparator.comparing(ZipEntry::getName)).toList();
            for (ZipEntry entry : entries) {
                Path target = extractDir.resolve(entry.getName()).normalize();
                if (!target.startsWith(extractDir)) {
                    throw new IOException("ZIP entry escapes extract dir: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry); OutputStream out = Files.newOutputStream(target)) {
                    in.transferTo(out);
                }
            }
        }
    }

    public static void zipDirectory(Path sourceDir, Path outputPptx) throws IOException {
        Files.createDirectories(outputPptx.getParent());
        Path absoluteSource = sourceDir.toAbsolutePath().normalize();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(outputPptx));
                Stream<Path> walk = Files.walk(absoluteSource)) {
            walk.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(path -> {
                        String entryName = absoluteSource.relativize(path).toString().replace('\\', '/');
                        try {
                            zip.putNextEntry(new ZipEntry(entryName));
                            Files.copy(path, zip);
                            zip.closeEntry();
                        } catch (IOException ex) {
                            throw new IllegalStateException("Failed to zip " + entryName, ex);
                        }
                    });
        }
    }
}
