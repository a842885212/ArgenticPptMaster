package domi.argenticpptmaster.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Normalized read-only capability index derived from slide_library.json. */
public record TemplateFillCapabilityIndex(
        String schemaVersion,
        int slideCount,
        Map<Integer, SlideCapability> slidesByIndex) {

    public TemplateFillCapabilityIndex {
        slidesByIndex = slidesByIndex == null ? Map.of() : Map.copyOf(slidesByIndex);
    }

    public Optional<SlideCapability> slide(int slideIndex) {
        return Optional.ofNullable(slidesByIndex.get(slideIndex));
    }

    public record SlideCapability(
            int slideIndex,
            String pageType,
            Map<String, TextSlotCapability> textSlots,
            Map<String, TableCapability> tables,
            Map<String, ChartCapability> charts) {

        public SlideCapability {
            textSlots = textSlots == null ? Map.of() : Map.copyOf(textSlots);
            tables = tables == null ? Map.of() : Map.copyOf(tables);
            charts = charts == null ? Map.of() : Map.copyOf(charts);
        }

        public boolean isCover() {
            return "cover_candidate".equals(pageType);
        }

        public boolean isEnding() {
            return "ending_candidate".equals(pageType);
        }
    }

    public record TextSlotCapability(
            String slotId,
            String role,
            Integer capacityVisualWidth,
            Double fontSizePx,
            boolean fontAdjustable) {
    }

    public record TableCapability(String tableId, int rowCount, int columnCount) {
        public boolean inBounds(int row, int col) {
            return row >= 0 && col >= 0 && row < rowCount && col < columnCount;
        }
    }

    public record ChartCapability(
            String chartId,
            String chartType,
            int categoryCount,
            int seriesCount,
            List<String> categories) {

        public ChartCapability {
            categories = categories == null ? List.of() : List.copyOf(categories);
        }
    }
}
