package com.lazydrop.modules.session.core.scheduler;

import com.lazydrop.modules.session.participant.service.DropSessionParticipantService;
import com.lazydrop.modules.session.core.service.DropSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
@Slf4j
public class DropSessionScheduler {

    private final DropSessionService dropSessionService;
    private final DropSessionParticipantService participantService;

    @Scheduled(fixedRate = 60000)
    public void expireSessions() {
        log.info("Running scheduled cleanup: expireSessions");
        dropSessionService.cleanUpExpiredSession();
    }


    @Scheduled(fixedRate = 300000)
    public void cleanupDisconnectedParticipants() {
        Duration maxDisconnectDuration = Duration.ofMinutes(5);
        log.info("Running scheduled cleanup: cleanupDisconnectedParticipants (maxDisconnectDuration = {} min)",
                maxDisconnectDuration.toMinutes());
        participantService.cleanupDisconnected(maxDisconnectDuration);
    }
}
