package domi.argenticpptmaster.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 为 native-enhance 托管 init/plan/MVP 编排提供与通用 Agent 隔离的有界执行器。 */
@Configuration
public class NativeEnhanceAsyncConfiguration {

    @Bean("nativeEnhanceExecutor")
    public ThreadPoolTaskExecutor nativeEnhanceExecutor(PptMasterProperties properties) {
        NativeEnhancePptxProperties limits = properties.nativeEnhancePptx();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(limits.maxConcurrentJobs());
        executor.setMaxPoolSize(limits.maxConcurrentJobs());
        executor.setQueueCapacity(limits.queueCapacity());
        executor.setThreadNamePrefix("native-enhance-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
