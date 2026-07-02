package domi.argenticpptmaster;

import domi.argenticpptmaster.config.AgentScopeProperties;
import domi.argenticpptmaster.config.PptMasterProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
@EnableConfigurationProperties({PptMasterProperties.class, AgentScopeProperties.class})
public class ArgenticPptMasterApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArgenticPptMasterApplication.class, args);
    }

}
