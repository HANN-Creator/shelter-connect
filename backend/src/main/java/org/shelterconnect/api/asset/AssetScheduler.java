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
    public AssetScheduler(AssetWorker worker) { this.worker=worker; }
    @Scheduled(fixedDelay=5000,initialDelay=10000)
    public void advance() {
        try { worker.tick(); }
        catch(RuntimeException e) { log.warn("Asset worker tick interrupted; type={}",e.getClass().getSimpleName()); }
    }
}
