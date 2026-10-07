package org.shelterconnect.api.asset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="app.assets.worker-enabled",havingValue="true")
public class AssetScheduler {
    private static final Logger log=LoggerFactory.getLogger(AssetScheduler.class);
    private final AssetWorker worker;
    private final StyledAssetWorker styled;
    private final StyledLessonWorker lessons;private final StyledLearningRecoveryWorker recovery;
    public AssetScheduler(AssetWorker worker,StyledAssetWorker styled,StyledLessonWorker lessons,StyledLearningRecoveryWorker recovery) { this.worker=worker;this.styled=styled;this.lessons=lessons;this.recovery=recovery; }
    @Scheduled(fixedDelay=5000,initialDelay=10000)
    public void advance() {
        try { styled.tick();worker.tick(); }
        catch(RuntimeException e) { log.warn("Asset worker tick interrupted; type={}",e.getClass().getSimpleName()); }
    }
    @Scheduled(fixedDelay=10000,initialDelay=20000)
    public void learn() {
        try { recovery.tick();lessons.tick(); }
        catch(RuntimeException e) { log.warn("Asset lesson tick interrupted; type={}",e.getClass().getSimpleName()); }
    }
}
