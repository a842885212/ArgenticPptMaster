package domi.argenticpptmaster.engine.file.fishaudio;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Fish Audio 旁白音色：对齐 cornel-script 对播默认两档（女大学生 / 奶油面包8088）。
 */
public final class FishVoicePresets {

    public static final String FEMALE_MANDARIN = "female_mandarin";
    public static final String MALE_MANDARIN = "male_mandarin";
    public static final String DEFAULT_VOICE = FEMALE_MANDARIN;
    public static final String PROVIDER = "fish";

    private static final Map<String, String> REFERENCE_IDS = Map.of(
            FEMALE_MANDARIN, "5c353fdb312f4888836a9a5680099ef0",
            MALE_MANDARIN, "28df7fe4d3ec45f692af03d0a372805b");

    private FishVoicePresets() {}

    public static Set<String> allowedVoices() {
        return REFERENCE_IDS.keySet();
    }

    public static boolean isAllowed(String voice) {
        return voice != null && REFERENCE_IDS.containsKey(voice.trim());
    }

    public static String requireReferenceId(String voice) {
        return referenceId(voice).orElseThrow(() -> new IllegalArgumentException(
                "unsupported fish voice: " + voice + "; allowed=" + REFERENCE_IDS.keySet()));
    }

    public static Optional<String> referenceId(String voice) {
        if (voice == null || voice.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(REFERENCE_IDS.get(voice.trim()));
    }

    /** 确认门禁展示用的有序列表。 */
    public static Map<String, String> voiceLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(FEMALE_MANDARIN, "女大学生");
        labels.put(MALE_MANDARIN, "奶油面包8088");
        return Map.copyOf(labels);
    }
}
