package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import domi.argenticpptmaster.domain.FillPlanStatus;
import domi.argenticpptmaster.domain.TemplateFillAnalysisSummary;
import domi.argenticpptmaster.domain.TemplateFillErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/** 读取模板填充分析产物并推导计划状态。 */
@Component
public class PptTemplateFillAnalysisReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public TemplateFillAnalysisSummary readSummary(Path slideLibraryPath) {
        if (!Files.isRegularFile(slideLibraryPath)) {
            throw analysisFailed("slide library file is missing");
        }
        try {
            JsonNode root = MAPPER.readTree(slideLibraryPath.toFile());
            String version = textOrNull(root.get("schema"));
            if (version == null || version.isBlank()) {
                throw analysisFailed("slide library schema version is missing");
            }
            JsonNode slideCountNode = root.get("slide_count");
            if (slideCountNode == null || !slideCountNode.isInt()) {
                throw analysisFailed("slide library slide_count is missing");
            }
            int slideCount = slideCountNode.asInt();
            JsonNode canvas = root.get("canvas_px");
            int width = canvas != null && canvas.has("width") ? canvas.get("width").asInt(0) : 0;
            int height = canvas != null && canvas.has("height") ? canvas.get("height").asInt(0) : 0;
            String formatLabel = width > 0 && height > 0 ? width + "x" + height : null;
            int textSlots = 0;
            int tables = 0;
            int charts = 0;
            JsonNode slides = root.get("slides");
            if (slides != null && slides.isArray()) {
                for (JsonNode slide : slides) {
                    textSlots += countArray(slide.get("slots"));
                    tables += countArray(slide.get("tables"));
                    charts += countArray(slide.get("charts"));
                }
            }
            return new TemplateFillAnalysisSummary(
                    slideCount, width, height, formatLabel, textSlots, tables, charts, version);
        } catch (PptTemplateFillExecutionException ex) {
            throw ex;
        } catch (IOException ex) {
            throw analysisFailed("failed to parse slide library: " + ex.getMessage());
        }
    }

    public FillPlanStatus deriveFillPlanStatus(Path analysisDir) {
        Path fillPlan = analysisDir.resolve("fill_plan.json");
        if (!Files.isRegularFile(fillPlan)) {
            return FillPlanStatus.NONE;
        }
        try {
            JsonNode root = MAPPER.readTree(fillPlan.toFile());
            String status = textOrNull(root.get("status"));
            if ("draft".equalsIgnoreCase(status)) {
                return FillPlanStatus.DRAFT;
            }
            Path checkReport = analysisDir.resolve("check_report.json");
            if (Files.isRegularFile(checkReport)) {
                JsonNode report = MAPPER.readTree(checkReport.toFile());
                if (report.get("summary") != null) {
                    return FillPlanStatus.VALIDATED;
                }
            }
            if ("confirmed".equalsIgnoreCase(status)) {
                return FillPlanStatus.CONFIRMED;
            }
            return FillPlanStatus.DRAFT;
        } catch (IOException ex) {
            return FillPlanStatus.NONE;
        }
    }

    public int[] readValidationCounts(Path checkReportPath) {
        if (!Files.isRegularFile(checkReportPath)) {
            return new int[] {0, 0};
        }
        try {
            JsonNode summary = MAPPER.readTree(checkReportPath.toFile()).get("summary");
            if (summary == null) {
                return new int[] {0, 0};
            }
            return new int[] {
                summary.has("warn") ? summary.get("warn").asInt(0) : 0,
                summary.has("error") ? summary.get("error").asInt(0) : 0
            };
        } catch (IOException ex) {
            return new int[] {0, 0};
        }
    }

    public int readPlanSlideCount(Path fillPlanPath) {
        if (!Files.isRegularFile(fillPlanPath)) {
            return 0;
        }
        try {
            JsonNode slides = MAPPER.readTree(fillPlanPath.toFile()).get("slides");
            return slides != null && slides.isArray() ? slides.size() : 0;
        } catch (IOException ex) {
            return 0;
        }
    }

    private static int countArray(JsonNode node) {
        return node != null && node.isArray() ? node.size() : 0;
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static PptTemplateFillExecutionException analysisFailed(String message) {
        return new PptTemplateFillExecutionException(
                "ANALYZE", message, TemplateFillErrorCode.TEMPLATE_ANALYSIS_FAILED);
    }
}
