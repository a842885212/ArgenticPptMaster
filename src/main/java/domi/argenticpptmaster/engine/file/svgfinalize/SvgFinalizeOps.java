// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgfinalize;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Orchestrate the default {@code finalize_svg.py} pipeline against a project directory.
 *
 * <p>Prep geometry materialize → embed icons → rematerialize → expand local {@code <use>}
 * → align/embed images → flatten text → atomic publish to {@code svg_final/}.</p>
 */
public final class SvgFinalizeOps {

    public static final boolean DEFAULT_COMPRESS = true;
    public static final int DEFAULT_MAX_DIMENSION = 2560;
    public static final double DEFAULT_IMAGE_SCALE = 2.0;

    private SvgFinalizeOps() {}

    public record FinalizeOutcome(boolean success, String message, int svgCount) {}

    public static FinalizeOutcome finalizeProject(Path projectDir) {
        return finalizeProject(projectDir, DEFAULT_COMPRESS, DEFAULT_MAX_DIMENSION, DEFAULT_IMAGE_SCALE);
    }

    public static FinalizeOutcome finalizeProject(
            Path projectDir, boolean compress, Integer maxDimension, double imageScale) {
        Path svgOutput = projectDir.resolve("svg_output");
        Path svgFinal = projectDir.resolve("svg_final");
        Path iconsDir = IconEmbedder.iconDirForProject(projectDir);

        if (!Files.isDirectory(svgOutput)) {
            return new FinalizeOutcome(false, "svg_output directory not found: " + svgOutput, 0);
        }
        List<Path> svgFiles;
        try {
            svgFiles = listSvgs(svgOutput);
        } catch (IOException ex) {
            return new FinalizeOutcome(false, "failed listing svg_output: " + ex.getMessage(), 0);
        }
        if (svgFiles.isEmpty()) {
            return new FinalizeOutcome(false, "No SVG files in svg_output", 0);
        }

        Path candidateDir = null;
        try {
            candidateDir = Files.createTempDirectory(svgFinal.getParent(), ".svg_final.candidate-");
            copyDirectory(svgOutput, candidateDir);
            if (!processCandidate(candidateDir, iconsDir, compress, maxDimension, imageScale)) {
                return new FinalizeOutcome(false, "SVG finalization failed before publish", svgFiles.size());
            }
            publishCandidateDirectory(candidateDir, svgFinal);
            candidateDir = null; // published / consumed
            return new FinalizeOutcome(true, "Done", svgFiles.size());
        } catch (Exception ex) {
            return new FinalizeOutcome(
                    false, "SVG finalization failed: " + ex.getMessage(), svgFiles.size());
        } finally {
            if (candidateDir != null) {
                deleteRecursivelyQuietly(candidateDir);
            }
        }
    }

    private static boolean processCandidate(
            Path candidateDir,
            Path iconsDir,
            boolean compress,
            Integer maxDimension,
            double imageScale)
            throws Exception {
        List<Path> svgs = listSvgs(candidateDir);
        for (Path svg : svgs) {
            try {
                GeometryMaterializer.materializeInFile(svg);
            } catch (Exception ex) {
                return false;
            }
        }

        for (Path svg : svgs) {
            try {
                IconEmbedder.processSvgFile(svg, iconsDir);
            } catch (Exception ex) {
                return false;
            }
        }
        for (Path svg : svgs) {
            try {
                GeometryMaterializer.materializeInFile(svg);
            } catch (Exception ex) {
                return false;
            }
        }
        for (Path svg : svgs) {
            try {
                LocalUseExpander.expandInFile(svg);
            } catch (Exception ex) {
                return false;
            }
        }

        int imgErrors = 0;
        for (Path svg : svgs) {
            AlignEmbedImages.Counts counts =
                    AlignEmbedImages.processFile(svg, compress, maxDimension, imageScale);
            imgErrors += counts.errors();
        }
        if (imgErrors > 0) {
            return false;
        }

        int flattenErrors = 0;
        for (Path svg : svgs) {
            try {
                FlattenTspan.processFile(svg);
            } catch (Exception ex) {
                flattenErrors++;
            }
        }
        return flattenErrors == 0;
    }

    /**
     * Atomic publish: move existing {@code svg_final} aside, replace with candidate, restore on failure.
     */
    static void publishCandidateDirectory(Path candidateDir, Path outputDir) throws IOException {
        if (Files.isSymbolicLink(outputDir)
                || (Files.exists(outputDir) && !Files.isDirectory(outputDir))) {
            throw new IOException("Output path must be a real directory: " + outputDir);
        }
        Path transactionDir = Files.createTempDirectory(outputDir.getParent(), ".svg_final.publish-");
        Path backupDir = transactionDir.resolve("previous");
        boolean preserveBackup = false;
        try {
            if (Files.isDirectory(outputDir)) {
                try {
                    moveReplace(outputDir, backupDir);
                    moveReplace(candidateDir, outputDir);
                } catch (Exception publishError) {
                    try {
                        if (Files.exists(backupDir)) {
                            if (Files.exists(outputDir)) {
                                moveReplace(outputDir, transactionDir.resolve("failed-publish"));
                            }
                            moveReplace(backupDir, outputDir);
                        }
                    } catch (Exception restoreError) {
                        if (!Files.exists(backupDir) && Files.exists(outputDir)) {
                            if (publishError instanceof IOException io) {
                                throw io;
                            }
                            throw new IOException(publishError);
                        }
                        preserveBackup = Files.exists(backupDir);
                        throw new IOException(
                                "Failed to publish svg_final and restore the previous directory; "
                                        + "recovery directory: "
                                        + transactionDir,
                                restoreError);
                    }
                    if (publishError instanceof IOException io) {
                        throw io;
                    }
                    throw new IOException(publishError);
                }
            } else {
                moveReplace(candidateDir, outputDir);
            }
        } finally {
            if (!preserveBackup) {
                deleteRecursivelyQuietly(transactionDir);
            }
        }
    }

    private static void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<Path> listSvgs(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> out = new ArrayList<>();
            stream.filter(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().toLowerCase().endsWith(".svg"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(out::add);
            return out;
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(dir);
                Path dest = target.resolve(relative);
                Files.createDirectories(dest);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(file);
                Files.copy(file, target.resolve(relative), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursivelyQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }
}
