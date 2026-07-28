package domi.argenticpptmaster.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 为 CPU/IO 密集的 beautify intake 提供与通用 Agent 隔离的有界执行器。 */
@Configuration
public class BeautifyPptxAsyncConfiguration {

    @Bean("beautifyAnalysisExecutor")
    public ThreadPoolTaskExecutor beautifyAnalysisExecutor(PptMasterProperties properties) {
        BeautifyPptxProperties limits = properties.beautifyPptx();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(limits.maxConcurrentAnalyses());
        executor.setMaxPoolSize(limits.maxConcurrentAnalyses());
        executor.setQueueCapacity(limits.queueCapacity());
        executor.setThreadNamePrefix("beautify-analysis-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
