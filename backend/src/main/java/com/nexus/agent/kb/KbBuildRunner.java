package com.nexus.agent.kb;

import com.nexus.agent.kb.model.KbBuildResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "kb.build", name = "enabled", havingValue = "true")
public class KbBuildRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KbBuildRunner.class);

    private final KbBuildProperties properties;
    private final KbBuildPipeline pipeline;
    private final ConfigurableApplicationContext context;

    public KbBuildRunner(KbBuildProperties properties, KbBuildPipeline pipeline, ConfigurableApplicationContext context) {
        this.properties = properties;
        this.pipeline = pipeline;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = 0;
        try {
            log.info("开始执行知识库构建：kbId={}, input={}", properties.getKbId(), properties.getInput());
            KbBuildResult result = pipeline.run();
            log.info("知识库构建完成：documentCount={}, chunkCount={}, vectorUpsertCount={}, tripleCount={}",
                    result.documentCount(), result.chunkCount(), result.vectorUpsertCount(), result.tripleCount());
        } catch (Exception e) {
            exitCode = 1;
            log.error("知识库构建失败: {}", e.getMessage(), e);
        } finally {
            if (properties.isExitAfterRun()) {
                log.info("kb.build.exit-after-run=true，关闭应用。");
                context.close();
                if (exitCode != 0) {
                    System.exit(exitCode);
                }
            }
        }
    }
}
