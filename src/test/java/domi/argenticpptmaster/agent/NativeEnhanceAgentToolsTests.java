package domi.argenticpptmaster.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.tool.Tool;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class NativeEnhanceAgentToolsTests {

    @Test
    void nativeEnhanceToolkitExcludesForbiddenToolNames() {
        Set<String> toolNames = Arrays.stream(NativeEnhanceAgentTools.class.getDeclaredMethods())
                .filter(method -> method.getAnnotation(Tool.class) != null)
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertThat(toolNames).contains("listSources", "readSourceMarkdown", "readSlideIndex",
                "readPlanDraft", "writePlanDraft", "writeNote", "inspectCheckpoints");
        assertThat(toolNames).noneMatch(name -> {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.contains("svg")
                    || lower.contains("template")
                    || lower.contains("beautify")
                    || lower.contains("image");
        });

        Set<String> registered = Arrays.stream(NativeEnhanceAgentTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Tool.class))
                .filter(tool -> tool != null)
                .map(Tool::name)
                .collect(Collectors.toSet());
        assertThat(registered).allMatch(name -> name.startsWith("list_native_enhance")
                || name.startsWith("read_native_enhance")
                || name.startsWith("write_native_enhance")
                || name.startsWith("inspect_native_enhance"));
    }
}
