package domi.argenticpptmaster.engine.file.importconvert;

import domi.argenticpptmaster.engine.file.importconvert.ImageManifestSupport.AssetState;
import domi.argenticpptmaster.engine.file.importconvert.ImageManifestSupport.PackageContentTypes;
import domi.argenticpptmaster.engine.file.importconvert.ImageManifestSupport.SavedAsset;
import domi.argenticpptmaster.engine.file.importconvert.ReadbackHyperlinks.LinkContext;
import domi.argenticpptmaster.engine.file.importconvert.ReadbackShapes.LeafShape;
import domi.argenticpptmaster.engine.ooxml.PptxPackage;
import domi.argenticpptmaster.engine.ooxml.RelationshipsSupport;
import domi.argenticpptmaster.engine.ooxml.RelationshipsSupport.Relationship;
import domi.argenticpptmaster.engine.ooxml.SlidePartRef;
import domi.argenticpptmaster.engine.ooxml.SlideParts;
import domi.argenticpptmaster.engine.ooxml.templatefill.TfXml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * PPTX→Markdown converter aligned with {@code source_to_md/ppt_to_md.py}（python-pptx pin &lt;0.7）：
 * reading-order leaf-shape 遍历（组扁平化 + (top,left) 排序）、超链接、chart 转录、
 * image manifest sidecar。消费方：import-sources（{@code JavaProjectWorkspace}）与 read-back 校验。
 */
public final class PptxToMarkdownConverter {

    private static final String IMAGE_REL_TYPE =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image";
    private static final String CHART_REL_TYPE =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart";
    private static final String NOTES_SLIDE_REL_TYPE =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesSlide";

    private PptxToMarkdownConverter() {}

    public static Path convert(Path pptxPath, Path markdownPath) throws ImportConversionException {
        Path extractDir = null;
        try {
            extractDir = Files.createTempDirectory("java-pptx-to-md-");
            PptxPackage.extract(pptxPath, extractDir);
            List<SlidePartRef> slides = SlideParts.read(extractDir);
            String assetDirName = stem(markdownPath) + "_files";
            Path assetDir = markdownPath.resolveSibling(assetDirName);
            ImageManifestSupport.resetGeneratedAssetDir(assetDir);
            Files.createDirectories(parentOrSelf(markdownPath));

            PackageContentTypes contentTypes = PackageContentTypes.parse(extractDir);
            AssetState assetState = new AssetState();
            Map<String, Integer> slideIndexByPart = new HashMap<>();
            for (SlidePartRef slide : slides) {
                slideIndexByPart.put(slide.partName(), slide.index());
            }

            StringBuilder md = new StringBuilder();
            md.append("# ").append(stem(pptxPath)).append("\n\n");
            md.append("- Source: `").append(pptxPath.getFileName()).append("`\n");
            md.append("- Total slides: ").append(slides.size()).append("\n\n");

            ImageCounters counters = new ImageCounters();
            for (SlidePartRef slide : slides) {
                md.append("## Slide ").append(slide.index()).append("\n\n");
                Path slideXml = extractDir.resolve(slide.partName());
                if (!Files.isRegularFile(slideXml)) {
                    md.append("_No extractable text content._\n\n");
                    continue;
                }
                Document doc = TfXml.parse(Files.readAllBytes(slideXml));
                Element root = doc.getDocumentElement();
                Path relsPath = extractDir.resolve(TfXml.relsNameForPart(slide.partName()));
                LinkContext linkContext = new LinkContext(
                        slide.partName(), parseRelationships(relsPath), slideIndexByPart);

                List<String> blocks = renderSlideBlocks(root, slide.index(), slide.partName(),
                        extractDir, assetDir, assetDirName, contentTypes, assetState, linkContext, counters);

                if (blocks.isEmpty()) {
                    md.append("_No extractable text content._\n\n");
                } else {
                    md.append(String.join("\n\n", blocks)).append("\n\n");
                }

                String notes = extractNotes(extractDir, relsPath, linkContext);
                if (!notes.isBlank()) {
                    md.append("### Speaker Notes\n\n").append(notes).append("\n\n");
                }
            }

            if (counters.assetDirUsed) {
                ImageManifestSupport.writeManifest(assetDir, assetState);
            } else {
                ImageManifestSupport.resetGeneratedAssetDir(assetDir);
            }
            Files.writeString(markdownPath, md.toString().strip() + "\n", StandardCharsets.UTF_8);
            return markdownPath;
        } catch (ImportConversionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ImportConversionException(
                    "presentation conversion failed for " + pptxPath + ": " + ex.getMessage(), ex);
        } finally {
            if (extractDir != null) {
                try {
                    deleteRecursively(extractDir);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            }
        }
    }

    private static List<String> renderSlideBlocks(
            Element root,
            int slideIndex,
            String sourcePart,
            Path extractDir,
            Path assetDir,
            String assetDirName,
            PackageContentTypes contentTypes,
            AssetState assetState,
            LinkContext linkContext,
            ImageCounters counters) throws IOException {
        List<String> blocks = new ArrayList<>();
        for (LeafShape leaf : ReadbackShapes.leafShapes(root)) {
            Element shape = leaf.element();

            if (ReadbackShapes.hasTable(shape)) {
                String table = ReadbackTextFrames.tableToMarkdown(shape);
                if (!table.isBlank()) {
                    blocks.add(table);
                }
                continue;
            }

            boolean isPictureShape = ReadbackShapes.isPicture(shape);
            String imageRelId = ReadbackShapes.imageRelationshipId(shape);
            boolean hasShapeImage = isPictureShape || imageRelId != null;
            boolean pictureHandled = false;
            if (hasShapeImage) {
                counters.imageRefCount++;
                Path partPath = resolveRelationshipPart(extractDir, sourcePart, linkContext, imageRelId);
                String imagePartName = partPath == null ? ""
                        : extractDir.relativize(partPath).toString().replace('\\', '/');
                SavedAsset saved = ImageManifestSupport.savePicture(shape, slideIndex, partPath, imagePartName,
                        contentTypes, assetDir, assetState, counters.imageCount + 1);
                if (saved == null) {
                    if (isPictureShape) {
                        blocks.add("> [Image] " + ReadbackShapes.shapeName(shape));
                        pictureHandled = true;
                    }
                } else {
                    if (saved.newAsset()) {
                        counters.imageCount++;
                        counters.assetDirUsed = true;
                    }
                    blocks.add("![Slide " + slideIndex + " Image " + counters.imageRefCount + "]("
                            + assetDirName + "/" + saved.filename() + ")");
                    if (isPictureShape) {
                        pictureHandled = true;
                    }
                }
            }
            if (pictureHandled) {
                continue;
            }

            Element txBody = ReadbackShapes.textBody(shape);
            if (txBody != null) {
                String text = ReadbackTextFrames.textFrameToMarkdown(txBody, shape, linkContext);
                if (!text.isEmpty()) {
                    blocks.add(text);
                    continue;
                }
            }

            String chartRelId = ReadbackShapes.chartRelationshipId(shape);
            if (chartRelId != null) {
                String name = ReadbackShapes.shapeName(shape);
                blocks.add(transcribeChart(extractDir, sourcePart, linkContext, chartRelId, name));
            }
        }
        return blocks;
    }

    private static String transcribeChart(
            Path extractDir, String sourcePart, LinkContext linkContext, String chartRelId, String name) {
        try {
            Path chartPart = resolveRelationshipPart(extractDir, sourcePart, linkContext, chartRelId);
            if (chartPart == null || !Files.isRegularFile(chartPart)) {
                return "> [Chart] " + name;
            }
            Document chartDoc = TfXml.parse(Files.readAllBytes(chartPart));
            return ChartTranscriber.chartToMarkdown(chartDoc, name);
        } catch (Exception ex) {
            // 对齐 Python：chart 解析异常落占位文本
            return "> [Chart] " + name;
        }
    }

    private static String extractNotes(Path extractDir, Path slideRelsPath, LinkContext slideContext) throws Exception {
        List<Relationship> slideRels = parseRelationships(slideRelsPath);
        for (Relationship rel : slideRels) {
            if (!NOTES_SLIDE_REL_TYPE.equals(rel.type())) {
                continue;
            }
            Path notesPart = extractDir.resolve(SlideParts.resolveRelationshipPart(
                    relsSourcePart(slideRelsPath), rel.target()));
            notesPart = notesPart.normalize();
            if (!notesPart.startsWith(extractDir) || !Files.isRegularFile(notesPart)) {
                continue;
            }
            Document doc = TfXml.parse(Files.readAllBytes(notesPart));
            Path notesRelsPath = extractDir.resolve(TfXml.relsNameForPart(
                    extractDir.relativize(notesPart).toString().replace('\\', '/')));
            String notesPartName = extractDir.relativize(notesPart).toString().replace('\\', '/');
            LinkContext notesContext = new LinkContext(notesPartName,
                    parseRelationships(notesRelsPath), slideContext.slideIndexByPart());
            List<String> blocks = new ArrayList<>();
            for (LeafShape leaf : ReadbackShapes.leafShapes(doc.getDocumentElement())) {
                Element txBody = ReadbackShapes.textBody(leaf.element());
                if (txBody == null) {
                    continue;
                }
                String text = ReadbackTextFrames.textFrameToMarkdown(txBody, leaf.element(), notesContext);
                if (!text.isEmpty()) {
                    blocks.add(text);
                }
            }
            return String.join("\n\n", blocks).strip();
        }
        return "";
    }

    /** 从 slide rels 的 rel 目标解析媒体/chart part 绝对路径；目标越出提取目录返回 null。 */
    private static Path resolveRelationshipPart(
            Path extractDir, String sourcePart, LinkContext context, String relId) {
        if (relId == null || relId.isBlank()) {
            return null;
        }
        for (Relationship rel : context.relationships()) {
            if (!relId.equals(rel.id())) {
                continue;
            }
            Path part = extractDir.resolve(SlideParts.resolveRelationshipPart(sourcePart, rel.target()))
                    .normalize();
            return part.startsWith(extractDir) && Files.isRegularFile(part) ? part : null;
        }
        return null;
    }

    private static List<Relationship> parseRelationships(Path relsPath) throws Exception {
        if (!Files.isRegularFile(relsPath)) {
            return List.of();
        }
        return RelationshipsSupport.parseRelationships(relsPath);
    }

    private static Path parentOrSelf(Path path) {
        Path parent = path.toAbsolutePath().getParent();
        return parent == null ? path : parent;
    }

    private static String relsSourcePart(Path slideRelsPath) {
        // e.g. <extractDir>/ppt/slides/_rels/slide1.xml.rels → ppt/slides/slide1.xml
        Path fileName = slideRelsPath.getFileName();
        String name = fileName == null ? "" : fileName.toString().replaceFirst("\\.rels$", "");
        Path parent = slideRelsPath.getParent();
        Path base = parent == null ? Path.of("") : parent.getParent();
        if (base == null) {
            base = Path.of("");
        }
        return base.resolve(name).toString().replace('\\', '/');
    }

    private static String stem(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        }
    }

    /** 图片引用计数（全局跨 slide，对齐 Python image_ref_count / image_count）。 */
    private static final class ImageCounters {
        private int imageRefCount;
        private int imageCount;
        private boolean assetDirUsed;
    }
}
