package com.yulong.chatagent.memory.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "chatagent.memory.l3", name = "enabled", havingValue = "true")
public class MemoryIndexReconciliationWorker {
    private final MemoryApplicationService service;
    private final MeterRegistry meters;
    public MemoryIndexReconciliationWorker(MemoryApplicationService service, ObjectProvider<MeterRegistry> meters) {
        this.service = service; this.meters = meters.getIfAvailable();
    }
    @Scheduled(fixedDelayString = "${chatagent.memory.l3.reconcile-delay-ms:60000}")
    public void reconcile() {
        int count = service.reconcile(100);
        if (meters != null) meters.counter("chatagent.memory.index.reconciled").increment(count);
    }
}
