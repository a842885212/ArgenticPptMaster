// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.nativeenhance;

import domi.argenticpptmaster.engine.ooxml.ContentTypesSupport;
import domi.argenticpptmaster.engine.ooxml.OoxmlConstants;
import domi.argenticpptmaster.engine.ooxml.RelationshipsSupport;
import domi.argenticpptmaster.engine.ooxml.SlidePartRef;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Embed pre-generated narration audio into slide OOXML (Python {@code _apply_audio}). */
public final class AudioApplier {

    private AudioApplier() {}

    public static Map<Integer, List<String>> nativeAudioCarriers(Path extractDir, List<SlidePartRef> slides)
            throws Exception {
        Map<Integer, List<String>> carriers = new LinkedHashMap<>();
        for (SlidePartRef slide : slides) {
            String xml = Files.readString(extractDir.resolve(slide.partName()), StandardCharsets.UTF_8);
            Document doc = SlideMotionSupport.parse(xml);
            List<String> names = new ArrayList<>();
            collectCarrierNames(doc.getDocumentElement(), names);
            names.sort(String::compareTo);
            if (!names.isEmpty()) {
                carriers.put(slide.index(), names);
            }
        }
        return carriers;
    }

    public static boolean applyAudio(
            Path extractDir,
            SlidePartRef slide,
            Path audioPath,
            SlideMotionSupport.EnterUpdate enter,
            boolean timingsEnabled,
            double narrationPadding,
            double narrationStartFloor,
            Double audioDuration)
            throws Exception {
        Path mediaDir = extractDir.resolve("ppt/media");
        Files.createDirectories(mediaDir);

        String ext = extension(audioPath);
        String mediaName = allocateMediaName(mediaDir, String.format("native_enhance_audio_%03d%s", slide.index(), ext));
        Files.copy(audioPath, mediaDir.resolve(mediaName), StandardCopyOption.REPLACE_EXISTING);
        String posterName = ensureAudioPoster(mediaDir);

        Path slideRels = RelationshipsSupport.relationshipFileForPart(extractDir, slide.partName());
        RelationshipsSupport.ensureRelsFile(slideRels);
        String mediaRid =
                RelationshipsSupport.appendRelationship(slideRels, NativeEnhanceConstants.MEDIA_REL_TYPE, "../media/" + mediaName);
        String audioRid =
                RelationshipsSupport.appendRelationship(slideRels, NativeEnhanceConstants.AUDIO_REL_TYPE, "../media/" + mediaName);
        String posterRid =
                RelationshipsSupport.appendRelationship(slideRels, NativeEnhanceConstants.IMAGE_REL_TYPE, "../media/" + posterName);

        Path slideXmlPath = extractDir.resolve(slide.partName());
        String slideXml = Files.readString(slideXmlPath, StandardCharsets.UTF_8);
        String sourceFingerprint = NarrationInjector.objectAnimationFingerprint(slideXml);
        SlideMotionSupport.TransitionSummary sourceTransition =
                SlideMotionSupport.readSlideTransitionXml(slideXml);

        double transitionDuration;
        if ("replace".equals(enter.policy())) {
            transitionDuration = enter.duration();
        } else if ("none".equals(enter.policy())) {
            transitionDuration = 0.0;
        } else {
            transitionDuration =
                    sourceTransition.durationMs() != null ? sourceTransition.durationMs() / 1000.0 : 0.0;
        }
        double narrationLeadIn =
                NarrationInjector.narrationLeadInSeconds(transitionDuration, narrationStartFloor);
        int shapeId = NarrationInjector.nextShapeId(slideXml);
        slideXml = NarrationInjector.injectNarration(
                slideXml, shapeId, mediaName, audioRid, mediaRid, posterRid, narrationLeadIn);

        SlideMotionSupport.AdvanceUpdate advance = SlideMotionSupport.AdvanceUpdate.preserve();
        Double duration = audioDuration;
        if (duration == null
                && (timingsEnabled || sourceTransition.advanceAfterMs() != null)) {
            duration = AudioProbe.probeDurationSeconds(audioPath).orElse(null);
        }
        if (!timingsEnabled && sourceTransition.advanceAfterMs() != null) {
            if (duration == null) {
                throw new IllegalStateException(
                        "Unable to validate narration against slide "
                                + slide.index()
                                + " auto-advance with ffprobe: "
                                + audioPath);
            }
            long requiredPlaybackMs = Math.round((narrationLeadIn + duration) * 1000);
            if (sourceTransition.advanceAfterMs() < requiredPlaybackMs) {
                throw new IllegalStateException(
                        "Slide "
                                + slide.index()
                                + " advances after "
                                + sourceTransition.advanceAfterMs()
                                + " ms, before delayed narration can finish at "
                                + requiredPlaybackMs
                                + " ms; enable timings or lengthen the source auto-advance");
            }
        }
        boolean wroteAdvance = false;
        if (timingsEnabled) {
            if (duration == null) {
                throw new IllegalStateException("Unable to read narration duration with ffprobe: " + audioPath);
            }
            advance = SlideMotionSupport.AdvanceUpdate.narration(narrationLeadIn + duration + narrationPadding);
        }
        if (!"preserve".equals(enter.policy()) || timingsEnabled) {
            SlideMotionSupport.MotionResult motion =
                    SlideMotionSupport.applySlideMotionXml(slideXml, enter, advance);
            slideXml = motion.slideXml();
            wroteAdvance = motion.wroteAdvance();
        }
        String afterFingerprint = NarrationInjector.objectAnimationFingerprint(slideXml);
        if (!java.util.Objects.equals(sourceFingerprint, afterFingerprint)) {
            throw new IllegalStateException(
                    "Slide " + slide.index() + " object animations changed while adding narration");
        }
        Files.writeString(slideXmlPath, slideXml, StandardCharsets.UTF_8);
        return timingsEnabled && wroteAdvance;
    }

    public static boolean applyTransitionOnly(
            Path extractDir, SlidePartRef slide, SlideMotionSupport.EnterUpdate enter) throws Exception {
        if ("preserve".equals(enter.policy())) {
            return false;
        }
        Path slideXmlPath = extractDir.resolve(slide.partName());
        String slideXml = Files.readString(slideXmlPath, StandardCharsets.UTF_8);
        String sourceFingerprint = NarrationInjector.objectAnimationFingerprint(slideXml);
        SlideMotionSupport.MotionResult motion = SlideMotionSupport.applySlideMotionXml(
                slideXml, enter, SlideMotionSupport.AdvanceUpdate.preserve());
        String afterFingerprint = NarrationInjector.objectAnimationFingerprint(motion.slideXml());
        if (!java.util.Objects.equals(sourceFingerprint, afterFingerprint)) {
            throw new IllegalStateException(
                    "Slide " + slide.index() + " object animations changed while updating the transition");
        }
        Files.writeString(slideXmlPath, motion.slideXml(), StandardCharsets.UTF_8);
        return true;
    }

    public static void updateContentTypes(Path extractDir, Set<String> noteParts, Set<String> audioExts)
            throws IOException {
        if (!noteParts.isEmpty()) {
            ContentTypesSupport.addNotesContentTypes(extractDir, noteParts);
        }
        Path path = extractDir.resolve("[Content_Types].xml");
        String contentTypes = Files.readString(path, StandardCharsets.UTF_8);
        for (String ext : audioExts.stream().sorted().toList()) {
            String contentType = NativeEnhanceConstants.AUDIO_CONTENT_TYPES.get(ext.toLowerCase(Locale.ROOT));
            if (contentType != null) {
                contentTypes = addDefault(contentTypes, ext.replace(".", ""), contentType);
            }
        }
        if (!audioExts.isEmpty()) {
            contentTypes = addDefault(contentTypes, "png", "image/png");
        }
        Files.writeString(path, contentTypes, StandardCharsets.UTF_8);
    }

    private static String addDefault(String contentTypes, String extension, String contentType) {
        String ext = extension.startsWith(".") ? extension.substring(1) : extension;
        if (contentTypes.contains("Extension=\"" + ext + "\"")) {
            return contentTypes;
        }
        String def = "  <Default Extension=\"" + ext + "\" ContentType=\"" + contentType + "\"/>";
        return contentTypes.replace("</Types>", def + "\n</Types>");
    }

    private static String ensureAudioPoster(Path mediaDir) throws IOException {
        String preferred = "native_enhance_audio_poster.png";
        try (Stream<Path> files = Files.list(mediaDir)) {
            for (Path candidate : files.filter(p -> p.getFileName().toString().startsWith("native_enhance_audio_poster")
                            && p.getFileName().toString().endsWith(".png"))
                    .sorted()
                    .toList()) {
                if (Files.isRegularFile(candidate)
                        && java.util.Arrays.equals(
                                Files.readAllBytes(candidate), NativeEnhanceConstants.AUDIO_MARKER_PNG_BYTES)) {
                    return candidate.getFileName().toString();
                }
            }
        }
        String posterName = allocateMediaName(mediaDir, preferred);
        Files.write(mediaDir.resolve(posterName), NativeEnhanceConstants.AUDIO_MARKER_PNG_BYTES);
        return posterName;
    }

    private static String allocateMediaName(Path mediaDir, String preferredName) throws IOException {
        Path preferred = mediaDir.resolve(preferredName);
        if (!Files.exists(preferred)) {
            return preferredName;
        }
        String stem = preferredName.contains(".")
                ? preferredName.substring(0, preferredName.lastIndexOf('.'))
                : preferredName;
        String suffix = preferredName.contains(".")
                ? preferredName.substring(preferredName.lastIndexOf('.'))
                : "";
        int index = 2;
        while (true) {
            String candidate = stem + "_" + index + suffix;
            if (!Files.exists(mediaDir.resolve(candidate))) {
                return candidate;
            }
            index++;
        }
    }

    private static String extension(Path audioPath) {
        String name = audioPath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : "";
    }

    private static void collectCarrierNames(Element node, List<String> names) {
        if (OoxmlConstants.PRESENTATION_NS.equals(node.getNamespaceURI())
                && "cNvPr".equals(node.getLocalName())) {
            String name = node.getAttribute("name");
            if (name != null && name.startsWith("native_enhance_audio_")) {
                names.add(name);
            }
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element el) {
                collectCarrierNames(el, names);
            }
        }
    }
}
