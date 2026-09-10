package com.salkcoding.oswl.service.notification;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
@Service @RequiredArgsConstructor
public class WebPushWorker {
    private final WebPushService service;
    private final WebPushTransport transport;
    @Scheduled(fixedDelayString="${oswl.web-push.poll-ms:30000}")
    @SchedulerLock(name="webPushDelivery",lockAtMostFor="PT5M",lockAtLeastFor="PT1S")
    public void deliver() {
        if(!transport.enabled())return;
        service.cleanup();
        for(Long id:service.due()) {
            var attempt=service.prepare(id); if(attempt==null)continue;
            int status=0;
            try{status=transport.send(attempt.subscription(),attempt.payload());}
            catch(InterruptedException e){Thread.currentThread().interrupt();return;}
            catch(Exception ignored){ /* Only status and attempt counts enter audit logs. */ }
            service.complete(id,status);
        }
    }
}
