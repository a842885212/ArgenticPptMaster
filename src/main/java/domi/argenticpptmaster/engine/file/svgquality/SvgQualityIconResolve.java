// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Icon placeholder resolution (from ppt-master {@code embed_icons.py}). */
final class SvgQualityIconResolve {

    private static final Pattern ICON_IDENTIFIER = Pattern.compile(
            "(?<library>[a-z0-9][a-z0-9-]*)/(?<name>[A-Za-z0-9][A-Za-z0-9._-]*)");

    private SvgQualityIconResolve() {}

    record IconPathResult(Path iconPath) {}

    static IconPathResult resolveIconPath(String iconName, Path iconsDir) {
        IconParts parts = splitIconIdentifier(iconName);
        Path iconPath = iconsDir.resolve(parts.library()).resolve(parts.name() + ".svg");
        Path resolvedRoot = iconsDir.toAbsolutePath().normalize();
        Path resolvedIcon = iconPath.toAbsolutePath().normalize();
        if (!resolvedIcon.startsWith(resolvedRoot)) {
            throw new IllegalArgumentException(
                    "data-icon escapes the project-local icon root: '" + iconName + "'");
        }
        return new IconPathResult(iconPath);
    }

    static Optional<String> suggestIconName(String iconName, Path iconsDir) {
        return casefoldIconNameInDir(iconName, iconsDir);
    }

    private static Optional<String> casefoldIconNameInDir(String iconName, Path iconsDir) {
        if (!Files.isDirectory(iconsDir)) {
            return Optional.empty();
        }
        IconParts parts;
        try {
            parts = splitIconIdentifier(iconName);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        Path searchDir = iconsDir.resolve(parts.library());
        if (!Files.isDirectory(searchDir)) {
            return Optional.empty();
        }
        String expectedFilename = (parts.name() + ".svg").toLowerCase(Locale.ROOT);
        List<Path> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.list(searchDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".svg"))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).equals(expectedFilename))
                    .sorted()
                    .forEach(matches::add);
        } catch (IOException e) {
            return Optional.empty();
        }
        if (matches.size() != 1) {
            return Optional.empty();
        }
        Path relative = iconsDir.relativize(matches.get(0));
        String withoutSuffix = relative.toString();
        if (withoutSuffix.endsWith(".svg")) {
            withoutSuffix = withoutSuffix.substring(0, withoutSuffix.length() - 4);
        }
        return Optional.of(withoutSuffix.replace('\\', '/'));
    }

    private record IconParts(String library, String name) {}

    private static IconParts splitIconIdentifier(String iconName) {
        Matcher match = ICON_IDENTIFIER.matcher(iconName);
        if (!match.matches()) {
            throw new IllegalArgumentException(
                    "data-icon must be a complete project-local library/name identifier: '"
                            + iconName
                            + "'");
        }
        return new IconParts(match.group("library"), match.group("name"));
    }
}
