// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

final class ViewBoxQualitySupport {

    private ViewBoxQualitySupport() {}

    /** Resolve the SVG directory the Python final-stage {@code check_directory} uses. */
    static Path resolveSvgDirectory(Path target) {
        Path svgOutput = target.resolve("svg_output");
        if (Files.isDirectory(svgOutput)) {
            return svgOutput;
        }
        return target;
    }

    static String compressViewBoxWhitespace(String viewBox) {
        return viewBox.strip().replaceAll("\\s+", " ");
    }

    static Optional<String> readRootViewBox(Path svgFile) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (ParserConfigurationException ignored) {
            // Best-effort hardening; continue if unsupported.
        }
        try {
            var builder = factory.newDocumentBuilder();
            try (InputStream in = Files.newInputStream(svgFile)) {
                Document document = builder.parse(in);
                Element root = document.getDocumentElement();
                if (root == null) {
                    return Optional.empty();
                }
                String viewBox = root.getAttribute("viewBox");
                if (viewBox == null || viewBox.isBlank()) {
                    return Optional.empty();
                }
                return Optional.of(viewBox);
            }
        } catch (ParserConfigurationException | SAXException | IOException e) {
            return Optional.empty();
        }
    }
}
