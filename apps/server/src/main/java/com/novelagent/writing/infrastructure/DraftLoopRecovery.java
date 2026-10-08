package com.novelagent.writing.infrastructure;

import com.novelagent.writing.domain.DraftLoopRun;
import java.util.List;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

/** 同一后端实例重启后保留产物并结束失联任务；不取消其他实例任务或自行重试模型。 */
@Component
public class DraftLoopRecovery {
    private final DraftLoopRunRepository runs;
    private final String identity;
    public DraftLoopRecovery(DraftLoopRunRepository runs, @Value("${app.instance-id:}") String configured,
            @Value("${server.port:8080}") String port, @Value("${app.role:all}") String role) {
        this.runs = runs;
        String host = System.getenv().getOrDefault("COMPUTERNAME", System.getenv().getOrDefault("HOSTNAME", "local"));
        identity = configured.isBlank() ? host + ":" + port + ":" + role : configured;
    }
    public String identity() { return identity; }
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void interruptLostWorkers() {
        runs.findByWorkerIdentityAndStatusIn(identity, List.of(DraftLoopRun.Status.PENDING, DraftLoopRun.Status.RUNNING))
                .forEach(run -> run.stop(DraftLoopRun.StopReason.INTERRUPTED));
    }
}
