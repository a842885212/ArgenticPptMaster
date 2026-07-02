package domi.argenticpptmaster;

import domi.argenticpptmaster.config.AgentScopeProperties;
import domi.argenticpptmaster.config.PptMasterProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * ArgenticPptMaster 应用程序的 Spring Boot 启动类。
 * <p>
 * 启用异步方法执行（{@link EnableAsync}）和配置属性绑定（{@link EnableConfigurationProperties}），
 * 作为整个 PPT 生成任务的入口点。
 * </p>
 */
@EnableAsync
@SpringBootApplication
@EnableConfigurationProperties({PptMasterProperties.class, AgentScopeProperties.class})
public class ArgenticPptMasterApplication {

    /**
     * 应用程序主入口方法。
     *
     * @param args 命令行参数，传递给 SpringApplication
     */
    public static void main(String[] args) {
        SpringApplication.run(ArgenticPptMasterApplication.class, args);
    }

}
