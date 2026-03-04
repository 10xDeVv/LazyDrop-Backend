package com.lazydrop.modules.session.core.service;

import com.lazydrop.common.exception.ForbiddenOperationException;
import com.lazydrop.common.exception.ResourceNotFoundException;
import com.lazydrop.modules.billing.service.PlanEnforcementService;
import com.lazydrop.modules.session.core.event.DropSessionEndedEvent;
import com.lazydrop.modules.session.core.model.DropSession;
import com.lazydrop.modules.session.core.model.SessionEndReason;
import com.lazydrop.modules.session.participant.service.DropSessionParticipantService;
import com.lazydrop.modules.subscription.model.PlanLimits;
import com.lazydrop.modules.subscription.service.SubscriptionService;
import com.lazydrop.modules.session.core.dto.DropSessionStatus;
import com.lazydrop.modules.session.core.repository.DropSessionRepository;
import com.lazydrop.modules.user.model.User;
import com.lazydrop.modules.websocket.MessageType;
import com.lazydrop.modules.websocket.WebSocketNotifier;
import com.lazydrop.modules.websocket.payload.DropSessionClosedPayload;
import com.lazydrop.modules.websocket.payload.DropSessionCreatedPayload;
import com.lazydrop.modules.websocket.payload.DropSessionExpiredPayload;
import com.lazydrop.utility.CodeUtility;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class DropSessionService {

    private final DropSessionRepository dropSessionRepository;
    private final SubscriptionService subscriptionService;
    private final DropSessionParticipantService participantService;
    private final PlanEnforcementService planEnforcementService;
    private final CodeUtility codeUtility;
    private final WebSocketNotifier webSocketNotifier;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Value("${app.join.base.url}")
    private String joinBaseUrl;

    private static final int MAX_CODE_RETRIES = 5;

    @Transactional
    public DropSession createDropSession(User owner){
        planEnforcementService.checkSessionCreationLimit(owner);
        PlanLimits limits = subscriptionService.getLimitsForUser(owner);

        Instant now = Instant.now();
        Instant expiresAt = now.plus(limits.sessionExpiryMinutes(), ChronoUnit.MINUTES);

        DropSession saved = null;
        for (int attempt = 0; attempt < MAX_CODE_RETRIES; attempt++) {
            String code = codeUtility.newAlphaNumericCode(8);
            DropSession session = DropSession.builder()
                    .owner(owner)
                    .code(code)
                    .createdAt(now)
                    .expiresAt(expiresAt)
                    .status(DropSessionStatus.OPEN)
                    .build();
            try {
                saved = dropSessionRepository.saveAndFlush(session);
                break;
            } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                if (attempt == MAX_CODE_RETRIES - 1) {
                    throw new IllegalStateException("Failed to generate a unique session code after " + MAX_CODE_RETRIES + " attempts", ex);
                }
                log.warn("Session code collision on attempt {}, retrying...", attempt + 1);
            }
        }

        participantService.ensureOwnerParticipant(saved, owner);

        DropSessionCreatedPayload payload = new DropSessionCreatedPayload(
                saved.getId().toString(),
                owner.getId().toString(),
                saved.getCreatedAt(),
                limits.sessionExpiryMinutes() * 60L
        );

        webSocketNotifier.sendEventAfterCommit(saved.getId().toString(), MessageType.DROP_SESSION_CREATED, payload);

        log.info("Created DropSession {} for user {}", saved.getId(), owner.getId());

        return saved;
    }

    public String generateQrCode(UUID sessionId){
        DropSession session = dropSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("DropSession with id " + sessionId + " not found"));
        return String.format(joinBaseUrl, session.getCode());
    }


    private void endSession(DropSession session, SessionEndReason reason){
        if (!session.isUsable()) return;
        session.end(reason);
        dropSessionRepository.save(session);

        if (reason == SessionEndReason.EXPIRED){
            webSocketNotifier.sendEventAfterCommit(
                    session.getId().toString(),
                    MessageType.DROP_SESSION_EXPIRED,
                    new DropSessionExpiredPayload(
                            session.getId().toString(),
                            session.getOwner().getId().toString(),
                            session.getExpiresAt()));

            log.info("Expired DropSession {} published DropSessionExpiredEvent", session.getId());
        }else {
            webSocketNotifier.sendEventAfterCommit(
                    session.getId().toString(),
                    MessageType.DROP_SESSION_CLOSED,
                    new DropSessionClosedPayload(
                            session.getId().toString(),
                            session.getOwner().getId().toString(),
                            session.getEndedAt()
                    )
            );

            log.info("Terminated DropSession {} (owner={})",
                    session.getId(), session.getOwner().getId());
        }

        applicationEventPublisher.publishEvent(new DropSessionEndedEvent(session.getId(), reason));
    }

    @Transactional
    public int cleanUpExpiredSessions(){
        Instant now = Instant.now();
        List<DropSession> toExpire =
                dropSessionRepository.findByStatusInAndExpiresAtBefore(
                        List.of(DropSessionStatus.OPEN, DropSessionStatus.CONNECTED),
                        now
                );

        toExpire.forEach(session -> endSession(session, SessionEndReason.EXPIRED));
        return toExpire.size();
    }

    @Transactional
    public void closeSessionById(UUID sessionId, User requester) {
        DropSession session = dropSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Drop Session not found"));

        ensureOwner(session, requester);
        endSession(session, SessionEndReason.CLOSED);
    }

    @Transactional(readOnly = true)
    public List<DropSession> getActiveSessionsForUser(User user) {
        return dropSessionRepository.findActiveForUser(
                user,
                List.of(DropSessionStatus.OPEN, DropSessionStatus.CONNECTED),
                Instant.now()
        );
    }

    @Transactional(readOnly = true)
    public Optional<DropSession> findByCode(String code){
        return dropSessionRepository.findByCode(code);
    }

    @Transactional(readOnly = true)
    public Optional<DropSession> findById(UUID id){
        return dropSessionRepository.findById(id);
    }

    private void ensureOwner(DropSession session, User requester) {
        if (!session.getOwner().getId().equals(requester.getId())) {
            throw new ForbiddenOperationException("Only the session owner can close this session.");
        }
    }
}
