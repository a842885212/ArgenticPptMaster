package domi.argenticpptmaster.engine.file.fishaudio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SpokenTextAndVoicePresetsTests {

    @Test
    void spokenTextStripsHeadingLinesOnly() {
        String markdown = """
                # 标题
                大家好。

                ## 小节
                继续讲解。
                """;
        assertThat(SpokenText.fromMarkdown(markdown)).isEqualTo("大家好。\n\n继续讲解。");
    }

    @Test
    void spokenTextKeepsListMarkers() {
        assertThat(SpokenText.fromMarkdown("- 第一点\n* 第二点")).isEqualTo("- 第一点\n* 第二点");
    }

    @Test
    void voicePresetsRejectUnknown() {
        assertThat(FishVoicePresets.isAllowed("female_mandarin")).isTrue();
        assertThat(FishVoicePresets.isAllowed("male_mandarin")).isTrue();
        assertThat(FishVoicePresets.isAllowed("zh-CN-YunjianNeural")).isFalse();
        assertThatThrownBy(() -> FishVoicePresets.requireReferenceId("edge-voice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported fish voice");
    }
}
