// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgtopptx.drawingml;

import domi.argenticpptmaster.engine.file.svgtopptx.lock.SpecLock;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mutable conversion state for one SVG → slide DrawingML pass. */
public final class ConvertContext {

    private final Path projectRoot;
    private final Path svgDir;
    private final SpecLock.ThemeColorSpec themeColors;
    private final SpecLock.ThemeFontSpec themeFonts;
    private final String primaryLanguage;
    private final boolean nativeObjectsEnabled;
    private final double translateX;
    private final double translateY;
    private final double scaleX;
    private final double scaleY;
    private final Map<String, String> inheritedStyles;
    private int nextShapeId = 2;
    private final Map<String, byte[]> mediaFiles = new LinkedHashMap<>();
    private final List<RelEntry> relEntries = new ArrayList<>();
    private final Map<String, byte[]> packageFiles = new LinkedHashMap<>();
    private final Map<String, String> contentTypeOverrides = new LinkedHashMap<>();
    private final List<long[]> animTargets = new ArrayList<>();

    public record RelEntry(String relId, String type, String target) {}

    public ConvertContext(
            Path projectRoot,
            Path svgDir,
            SpecLock.ThemeColorSpec themeColors,
            SpecLock.ThemeFontSpec themeFonts,
            String primaryLanguage,
            boolean nativeObjectsEnabled) {
        this(
                projectRoot,
                svgDir,
                themeColors,
                themeFonts,
                primaryLanguage,
                nativeObjectsEnabled,
                0,
                0,
                1,
                1,
                Map.of());
    }

    private ConvertContext(
            Path projectRoot,
            Path svgDir,
            SpecLock.ThemeColorSpec themeColors,
            SpecLock.ThemeFontSpec themeFonts,
            String primaryLanguage,
            boolean nativeObjectsEnabled,
            double translateX,
            double translateY,
            double scaleX,
            double scaleY,
            Map<String, String> inheritedStyles) {
        this.projectRoot = projectRoot;
        this.svgDir = svgDir;
        this.themeColors = themeColors;
        this.themeFonts = themeFonts;
        this.primaryLanguage = primaryLanguage;
        this.nativeObjectsEnabled = nativeObjectsEnabled;
        this.translateX = translateX;
        this.translateY = translateY;
        this.scaleX = scaleX;
        this.scaleY = scaleY;
        this.inheritedStyles = Map.copyOf(inheritedStyles);
    }

    public ConvertContext child(
            double dx, double dy, double sx, double sy, Map<String, String> styleOverrides) {
        Map<String, String> merged = new LinkedHashMap<>(inheritedStyles);
        if (styleOverrides != null) {
            merged.putAll(styleOverrides);
        }
        ConvertContext child = new ConvertContext(
                projectRoot,
                svgDir,
                themeColors,
                themeFonts,
                primaryLanguage,
                nativeObjectsEnabled,
                translateX + scaleX * dx,
                translateY + scaleY * dy,
                scaleX * sx,
                scaleY * sy,
                merged);
        child.nextShapeId = this.nextShapeId;
        return child;
    }

    public void syncFromChild(ConvertContext child) {
        this.nextShapeId = child.nextShapeId;
        this.mediaFiles.putAll(child.mediaFiles);
        this.relEntries.addAll(child.relEntries);
        this.packageFiles.putAll(child.packageFiles);
        this.contentTypeOverrides.putAll(child.contentTypeOverrides);
        this.animTargets.addAll(child.animTargets);
    }

    public int claimShapeId() {
        return nextShapeId++;
    }

    public double mapX(double x) {
        return x * scaleX + translateX;
    }

    public double mapY(double y) {
        return y * scaleY + translateY;
    }

    public double mapW(double w) {
        return w * scaleX;
    }

    public double mapH(double h) {
        return h * scaleY;
    }

    public String style(String name) {
        return inheritedStyles.get(name);
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public Path svgDir() {
        return svgDir;
    }

    public SpecLock.ThemeColorSpec themeColors() {
        return themeColors;
    }

    public SpecLock.ThemeFontSpec themeFonts() {
        return themeFonts;
    }

    public String primaryLanguage() {
        return primaryLanguage;
    }

    public boolean nativeObjectsEnabled() {
        return nativeObjectsEnabled;
    }

    public Map<String, byte[]> mediaFiles() {
        return mediaFiles;
    }

    public List<RelEntry> relEntries() {
        return relEntries;
    }

    public Map<String, byte[]> packageFiles() {
        return packageFiles;
    }

    public Map<String, String> contentTypeOverrides() {
        return contentTypeOverrides;
    }

    public List<long[]> animTargets() {
        return animTargets;
    }

    public String nextMediaRelId() {
        return "rId" + (relEntries.size() + 2); // rId1 reserved for slideLayout
    }
}
