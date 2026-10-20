// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Blocking external image reference checks (subset of {@code _check_image_references}). */
final class ImageReferenceQualitySupport {

    private ImageReferenceQualitySupport() {}

    static List<String> checkMissingExternalImages(Path svgFile, Document document) {
        Path svgDir = svgFile.getParent();
        if (svgDir == null) {
            return List.of();
        }
        List<String> errors = new ArrayList<>();
        for (Element image : SvgQualityVisibleImages.visibleImageElements(document)) {
            String href = SvgQualityXml.imageHref(image);
            if (href == null || href.isBlank()) {
                continue;
            }
            if (href.startsWith("data:")) {
                continue;
            }
            var candidate = SvgQualityResourcePaths.externalImageReferencePath(svgDir, href);
            if (candidate.isEmpty()) {
                continue;
            }
            if (!Files.isRegularFile(candidate.get())) {
                errors.add("External image not found: " + href);
            }
        }
        return errors;
    }
}
