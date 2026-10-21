// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgfinalize;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Align (slice/meet) and Base64-embed {@code <image>} references.
 *
 * <p>Port of {@code svg_finalize/align_embed_images.py} for the service SVG subset.
 * Image compression uses {@link ImageIO} and is intentionally not bit-identical to PIL.</p>
 */
public final class AlignEmbedImages {

    private static final Set<String> OFFICE_VECTOR_EXTENSIONS = Set.of(".emf", ".wmf");
    private static final Pattern SVG_DOC_START = Pattern.compile(
            "\\A(?:\\uFEFF)?[ \\t\\r\\n]*(?:<\\?xml(?=[ \\t\\r\\n])(?:[^?]|\\?(?!>))\\?>[ \\t\\r\\n]*)?"
                    + "(?:(?:"
                    + "<!--(?:[^-]|-(?!-))*-->"
                    + "|<!DOCTYPE[ \\t\\r\\n]+svg(?=[ \\t\\r\\n\\[>])"
                    + "(?:[^>\"']|\"[^\"]*\"|'[^']*')*>"
                    + ")[ \\t\\r\\n]*)*"
                    + "<svg(?:[ \\t\\r\\n:]|/?>|\\z)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private AlignEmbedImages() {}

    public record Counts(int processed, int errors, int officeVectors) {}

    public static Counts processFile(
            Path svgPath, boolean compress, Integer maxDimension, double imageScale) throws Exception {
        Document document = SvgFinalizeXml.parse(svgPath);
        Path svgDir = svgPath.getParent().toAbsolutePath().normalize();
        Path projectRoot = projectRootForSvg(svgPath).toAbsolutePath().normalize();

        int processed = 0;
        int errors = 0;
        int officeVectors = 0;

        for (Element image : SvgFinalizeXml.descendants(document.getDocumentElement(), "image")) {
            String href = SvgFinalizeXml.href(image);
            if (href == null || href.isBlank()) {
                continue;
            }
            if (href.toLowerCase(Locale.ROOT).startsWith("data:")) {
                continue;
            }
            Optional<Path> resolved = resolveExternalImage(svgDir, href, projectRoot);
            if (resolved.isEmpty()) {
                errors++;
                continue;
            }
            Path imgPath = resolved.get();
            String suffix = imgPath.getFileName().toString().toLowerCase(Locale.ROOT);
            int dot = suffix.lastIndexOf('.');
            String ext = dot >= 0 ? suffix.substring(dot) : "";
            if (OFFICE_VECTOR_EXTENSIONS.contains(ext)) {
                officeVectors++;
                continue;
            }
            byte[] rawBytes;
            try {
                rawBytes = Files.readAllBytes(imgPath);
            } catch (Exception ex) {
                errors++;
                continue;
            }
            if (isSvgImage(imgPath, rawBytes)) {
                embedRaw(image, imgPath, rawBytes);
                processed++;
                continue;
            }
            BufferedImage img;
            try {
                img = ImageIO.read(new ByteArrayInputStream(rawBytes));
            } catch (Exception ex) {
                img = null;
            }
            if (img == null) {
                errors++;
                continue;
            }

            double boxX = parseFloat(attr(image, "x"), 0);
            double boxY = parseFloat(attr(image, "y"), 0);
            double boxW = parseFloat(attr(image, "width"), 0);
            double boxH = parseFloat(attr(image, "height"), 0);
            if (!Double.isFinite(boxW) || !Double.isFinite(boxH) || boxW <= 0 || boxH <= 0) {
                errors++;
                continue;
            }

            String parAttr = attr(image, "preserveAspectRatio");
            if (parAttr != null) {
                parAttr = parAttr.strip();
            } else {
                parAttr = "";
            }

            BufferedImage finalImg = img;
            double newX = boxX;
            double newY = boxY;
            double newW = boxW;
            double newH = boxH;
            boolean transformed = false;
            double targetBoxW = boxW;
            double targetBoxH = boxH;
            boolean preserveStretch = false;
            boolean preserveSlice = false;

            if (!parAttr.isEmpty()) {
                String[] parsed = parsePreserveAspectRatio(parAttr);
                String align = parsed[0];
                String mode = parsed[1];
                if ("none".equals(align)) {
                    preserveStretch = true;
                } else if ("slice".equals(mode)) {
                    double[] anchor = getCropAnchor(align);
                    finalImg = cropImageToSize(img, boxW, boxH, anchor[0], anchor[1]);
                    transformed = true;
                    double croppedRatio = (double) finalImg.getWidth() / finalImg.getHeight();
                    double boxRatio = boxW / boxH;
                    preserveSlice = !almostEqual(croppedRatio, boxRatio);
                } else {
                    double[] fitted = calculateFittedDimensions(
                            img.getWidth(), img.getHeight(), boxW, boxH, "meet");
                    newW = fitted[0];
                    newH = fitted[1];
                    newX = boxX + fitted[2];
                    newY = boxY + fitted[3];
                    targetBoxW = newW;
                    targetBoxH = newH;
                }
            }

            int[] target = targetSize(targetBoxW, targetBoxH, maxDimension, imageScale);
            BufferedImage resized = downscaleToTarget(finalImg, target[0], target[1]);
            if (resized != finalImg) {
                transformed = true;
                finalImg = resized;
            }

            String originalMime = mimeType(imgPath.getFileName().toString(), rawBytes);
            String dataUri = encodeToDataUri(
                    finalImg,
                    originalMime,
                    compress,
                    transformed ? null : rawBytes);
            if (dataUri == null) {
                errors++;
                continue;
            }
            SvgFinalizeXml.setHref(image, dataUri);
            image.setAttribute("x", formatNumber(newX));
            image.setAttribute("y", formatNumber(newY));
            image.setAttribute("width", formatNumber(newW));
            image.setAttribute("height", formatNumber(newH));
            if (preserveStretch) {
                image.setAttribute("preserveAspectRatio", "none");
            } else if (preserveSlice) {
                image.setAttribute("preserveAspectRatio", parAttr);
            } else if (image.hasAttribute("preserveAspectRatio")) {
                image.removeAttribute("preserveAspectRatio");
            }
            processed++;
        }

        if (processed > 0 && errors == 0) {
            SvgFinalizeXml.write(document, svgPath);
        }
        return new Counts(processed, errors, officeVectors);
    }

    public static int countOfficeVectorRefs(Path svgPath) {
        try {
            Document document = SvgFinalizeXml.parse(svgPath);
            Path svgDir = svgPath.getParent().toAbsolutePath().normalize();
            Path projectRoot = projectRootForSvg(svgPath).toAbsolutePath().normalize();
            int count = 0;
            for (Element image : SvgFinalizeXml.descendants(document.getDocumentElement(), "image")) {
                String href = SvgFinalizeXml.href(image);
                if (href == null || href.startsWith("data:")) {
                    continue;
                }
                Optional<Path> resolved = resolveExternalImage(svgDir, href, projectRoot);
                if (resolved.isEmpty()) {
                    continue;
                }
                String name = resolved.get().getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".emf") || name.endsWith(".wmf")) {
                    count++;
                }
            }
            return count;
        } catch (Exception ex) {
            return 0;
        }
    }

    private static void embedRaw(Element image, Path imgPath, byte[] rawBytes) {
        String mime = mimeType(imgPath.getFileName().toString(), rawBytes);
        String b64 = Base64.getEncoder().encodeToString(rawBytes);
        SvgFinalizeXml.setHref(image, "data:" + mime + ";base64," + b64);
    }

    private static String encodeToDataUri(
            BufferedImage img, String originalMime, boolean compress, byte[] fallbackBytes) {
        String mimeType = "image/jpeg".equals(originalMime) ? "image/jpeg" : "image/png";
        try {
            BufferedImage prepared = normalizeForSave(img, mimeType);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            String format = "image/jpeg".equals(mimeType) ? "jpg" : "png";
            if (!ImageIO.write(prepared, format, buf)) {
                return null;
            }
            byte[] encoded = buf.toByteArray();
            byte[] chosen = encoded;
            if (fallbackBytes != null
                    && mimeType.equals(originalMime)
                    && fallbackBytes.length < encoded.length) {
                chosen = fallbackBytes;
            }
            // compress flag kept for API parity; ImageIO already produces compact PNG/JPEG.
            if (!compress && fallbackBytes != null && mimeType.equals(originalMime)) {
                chosen = fallbackBytes;
            }
            return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(chosen);
        } catch (Exception ex) {
            return null;
        }
    }

    private static BufferedImage normalizeForSave(BufferedImage img, String mimeType) {
        if ("image/jpeg".equals(mimeType)) {
            if (img.getColorModel().hasAlpha()) {
                BufferedImage rgb =
                        new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rgb.createGraphics();
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, img.getWidth(), img.getHeight());
                g.drawImage(img, 0, 0, null);
                g.dispose();
                return rgb;
            }
            if (img.getType() != BufferedImage.TYPE_INT_RGB) {
                BufferedImage rgb =
                        new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rgb.createGraphics();
                g.drawImage(img, 0, 0, null);
                g.dispose();
                return rgb;
            }
        }
        return img;
    }

    private static BufferedImage cropImageToSize(
            BufferedImage img, double targetWidth, double targetHeight, double xAnchor, double yAnchor) {
        int imgWidth = img.getWidth();
        int imgHeight = img.getHeight();
        double targetRatio = targetWidth / targetHeight;
        double imgRatio = (double) imgWidth / imgHeight;
        int cropWidth;
        int cropHeight;
        if (imgRatio > targetRatio) {
            cropHeight = imgHeight;
            cropWidth = Math.max(1, Math.min(imgWidth, (int) Math.round(imgHeight * targetRatio)));
        } else {
            cropWidth = imgWidth;
            cropHeight = Math.max(1, Math.min(imgHeight, (int) Math.round(imgWidth / targetRatio)));
        }
        int left = (int) ((imgWidth - cropWidth) * xAnchor);
        int top = (int) ((imgHeight - cropHeight) * yAnchor);
        return img.getSubimage(left, top, cropWidth, cropHeight);
    }

    private static BufferedImage downscaleToTarget(BufferedImage img, int targetW, int targetH) {
        int width = img.getWidth();
        int height = img.getHeight();
        if (width <= 0 || height <= 0) {
            return img;
        }
        double ratio = Math.min(Math.min(targetW / (double) width, targetH / (double) height), 1.0);
        if (ratio >= 1.0) {
            return img;
        }
        int newW = Math.max(1, (int) Math.round(width * ratio));
        int newH = Math.max(1, (int) Math.round(height * ratio));
        BufferedImage out = new BufferedImage(
                newW, newH, img.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, newW, newH, null);
        g.dispose();
        return out;
    }

    private static int[] targetSize(double boxW, double boxH, Integer maxDimension, double imageScale) {
        int targetW = Math.max(1, (int) Math.round(boxW * Math.max(imageScale, 1.0)));
        int targetH = Math.max(1, (int) Math.round(boxH * Math.max(imageScale, 1.0)));
        if (maxDimension != null && Math.max(targetW, targetH) > maxDimension) {
            double ratio = maxDimension / (double) Math.max(targetW, targetH);
            targetW = Math.max(1, (int) Math.round(targetW * ratio));
            targetH = Math.max(1, (int) Math.round(targetH * ratio));
        }
        return new int[] {targetW, targetH};
    }

    static String[] parsePreserveAspectRatio(String attr) {
        if (attr == null || attr.isBlank()) {
            return new String[] {"xMidYMid", "meet"};
        }
        String[] parts = attr.strip().split("\\s+");
        String align = parts.length > 0 ? parts[0] : "xMidYMid";
        String mode = parts.length > 1 ? parts[1] : "meet";
        return new String[] {align, mode};
    }

    static double[] getCropAnchor(String align) {
        double xAnchor = 0.5;
        double yAnchor = 0.5;
        if (align.contains("xMin")) {
            xAnchor = 0.0;
        } else if (align.contains("xMax")) {
            xAnchor = 1.0;
        }
        if (align.contains("YMin")) {
            yAnchor = 0.0;
        } else if (align.contains("YMax")) {
            yAnchor = 1.0;
        }
        return new double[] {xAnchor, yAnchor};
    }

    static double[] calculateFittedDimensions(
            int imgWidth, int imgHeight, double boxWidth, double boxHeight, String mode) {
        double imgRatio = imgWidth / (double) imgHeight;
        double boxRatio = boxWidth / boxHeight;
        double newWidth;
        double newHeight;
        if ("meet".equals(mode)) {
            if (imgRatio > boxRatio) {
                newWidth = boxWidth;
                newHeight = boxWidth / imgRatio;
            } else {
                newHeight = boxHeight;
                newWidth = boxHeight * imgRatio;
            }
        } else {
            if (imgRatio > boxRatio) {
                newHeight = boxHeight;
                newWidth = boxHeight * imgRatio;
            } else {
                newWidth = boxWidth;
                newHeight = boxWidth / imgRatio;
            }
        }
        double offsetX = (boxWidth - newWidth) / 2;
        double offsetY = (boxHeight - newHeight) / 2;
        return new double[] {newWidth, newHeight, offsetX, offsetY};
    }

    private static boolean isSvgImage(Path imgPath, byte[] rawBytes) {
        if (imgPath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".svg")) {
            return true;
        }
        String head = new String(rawBytes, 0, Math.min(rawBytes.length, 512), StandardCharsets.UTF_8)
                .stripLeading()
                .toLowerCase(Locale.ROOT);
        return head.startsWith("<svg") || (head.startsWith("<?xml") && head.contains("<svg"));
    }

    static String mimeType(String filename, byte[] fileBytes) {
        if (fileBytes != null) {
            if (startsWith(fileBytes, new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'})) {
                return "image/png";
            }
            if (fileBytes.length >= 3
                    && (fileBytes[0] & 0xff) == 0xff
                    && (fileBytes[1] & 0xff) == 0xd8
                    && (fileBytes[2] & 0xff) == 0xff) {
                return "image/jpeg";
            }
            if (startsWith(fileBytes, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                    || startsWith(fileBytes, "GIF89a".getBytes(StandardCharsets.US_ASCII))) {
                return "image/gif";
            }
            if (fileBytes.length >= 12
                    && startsWith(fileBytes, "RIFF".getBytes(StandardCharsets.US_ASCII))
                    && fileBytes[8] == 'W'
                    && fileBytes[9] == 'E'
                    && fileBytes[10] == 'B'
                    && fileBytes[11] == 'P') {
                return "image/webp";
            }
            String asText = new String(fileBytes, StandardCharsets.UTF_8);
            if (SVG_DOC_START.matcher(asText).lookingAt()) {
                return "image/svg+xml";
            }
        }
        String ext = filename.toLowerCase(Locale.ROOT);
        int dot = ext.lastIndexOf('.');
        ext = dot >= 0 ? ext.substring(dot + 1) : "";
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            default -> "application/octet-stream";
        };
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static Optional<Path> resolveExternalImage(Path svgDir, String href, Path projectRoot) {
        try {
            URI parsed = new URI(href.strip());
            if (parsed.getScheme() != null && !parsed.getScheme().isEmpty()) {
                return Optional.empty();
            }
            if (parsed.getHost() != null || parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
                return Optional.empty();
            }
            String pathPart = parsed.getPath();
            if (pathPart == null || pathPart.isEmpty()) {
                return Optional.empty();
            }
            String decoded = URLDecoder.decode(pathPart, StandardCharsets.UTF_8);
            Path relative = Path.of(decoded);
            if (relative.isAbsolute()) {
                return Optional.empty();
            }
            Path resolved = svgDir.resolve(relative).toAbsolutePath().normalize();
            if (!resolved.startsWith(projectRoot)) {
                return Optional.empty();
            }
            return Optional.of(resolved);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private static Path projectRootForSvg(Path svgPath) {
        Path base = Files.isDirectory(svgPath) ? svgPath : svgPath.getParent();
        if (base == null) {
            return svgPath;
        }
        String name = base.getFileName().toString();
        if (name.equals("svg_output")
                || name.equals("svg_final")
                || name.startsWith(".svg_final.candidate-")) {
            Path parent = base.getParent();
            return parent != null ? parent : base;
        }
        return base;
    }

    private static double parseFloat(String val, double defaultValue) {
        if (val == null || val.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(val.strip().replaceAll("(px|pt|em|%|rem)$", ""));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static String formatNumber(double n) {
        if (Math.abs(n - Math.round(n)) < 1e-6) {
            return Long.toString(Math.round(n));
        }
        String s = String.format(Locale.ROOT, "%.2f", n);
        while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() ? "0" : s;
    }

    private static boolean almostEqual(double a, double b) {
        return Math.abs(a - b) <= Math.max(1e-9, 1e-6 * Math.max(Math.abs(a), Math.abs(b)));
    }

    private static String attr(Element element, String name) {
        return element.hasAttribute(name) ? element.getAttribute(name) : null;
    }
}
