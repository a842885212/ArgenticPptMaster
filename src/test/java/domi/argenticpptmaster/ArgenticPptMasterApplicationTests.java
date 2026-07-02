package domi.argenticpptmaster;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Spring Boot 上下文加载测试。
 * <p>
 * 验证应用程序上下文能够正常启动并加载所有 Bean 配置，
 * 确保项目基础依赖（数据源、自动配置等）无缺失。
 */
@SpringBootTest
class ArgenticPptMasterApplicationTests {

    /**
     * 验证 Spring 应用上下文能够成功加载。
     * 若此测试失败，说明项目存在配置错误或 Bean 冲突。
     */
    @Test
    void contextLoads() {
    }

}
