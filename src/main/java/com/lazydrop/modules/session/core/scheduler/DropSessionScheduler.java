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

    @Scheduled(fixedRate = 60_000)
    public void expireSessions() {
        log.debug("Tick: expireSessions");
        int expired = dropSessionService.cleanUpExpiredSessions();
        if (expired > 0) {
            log.info("Expired {} session(s)", expired);
        }
    }

    @Scheduled(fixedRate = 300_000)
    public void cleanupDisconnectedParticipants() {
        log.debug("Tick: cleanupDisconnectedParticipants");
        Duration maxDisconnect = Duration.ofMinutes(5);
        int removed = participantService.cleanupDisconnected(maxDisconnect);
        if (removed > 0) {
            log.info("Removed {} disconnected participant(s)", removed);
        }
    }
}
