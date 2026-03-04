package com.lazydrop.modules.session.participant.service;

import com.lazydrop.common.exception.ResourceNotFoundException;
import com.lazydrop.modules.billing.service.PlanEnforcementService;
import com.lazydrop.modules.session.participant.dto.ParticipantSettingsRequest;
import com.lazydrop.modules.session.participant.dto.ParticipantSettingsResponse;
import com.lazydrop.modules.session.core.model.DropSession;
import com.lazydrop.modules.session.participant.dto.ParticipantRole;
import com.lazydrop.modules.session.note.repository.DropSessionNoteRepository;
import com.lazydrop.modules.session.participant.model.DropSessionParticipant;
import com.lazydrop.modules.websocket.MessageType;
import com.lazydrop.modules.session.participant.repository.DropSessionParticipantRepository;
import com.lazydrop.modules.user.model.User;
import com.lazydrop.modules.websocket.WebSocketNotifier;
import com.lazydrop.modules.websocket.payload.PeerJoinedPayload;
import com.lazydrop.modules.websocket.payload.PeerLeftPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DropSessionParticipantService {


    private final DropSessionParticipantRepository participantRepository;
    private final WebSocketNotifier webSocketNotifier;
    private final PlanEnforcementService planEnforcementService;
    private final DropSessionNoteRepository noteRepository;



    @Transactional
    public DropSessionParticipant joinSession(DropSession session, User user){
        session.assertUsable();

        long currentParticipants = participantRepository.countByDropSession(session);
        planEnforcementService.checkParticipantLimit(session.getOwner(), currentParticipants);

        DropSessionParticipant participant = participantRepository.findByDropSessionAndUser(session, user)
                .orElseGet(() -> {
                    DropSessionParticipant p = new DropSessionParticipant();
                    p.setDropSession(session);
                    p.setUser(user);
                    p.setRole(session.getOwner().getId().equals(user.getId()) ? ParticipantRole.OWNER : ParticipantRole.PEER);
                    try {
                        return participantRepository.saveAndFlush(p);
                    } catch (DataIntegrityViolationException ex) {
                        return participantRepository.findByDropSessionAndUser(session, user)
                                .orElseThrow(() -> ex);
                    }
                });

        PeerJoinedPayload payload = new PeerJoinedPayload(
                participant.getId().toString(),
                participant.getUser().getId().toString(),
                participant.getRole().name(),
                user.isGuest() ? "Guest" : user.getEmail(),
                user.isGuest()
        );

        webSocketNotifier.sendEventAfterCommit(session.getId().toString(), MessageType.PEER_JOINED, payload);

        return participant;
    }

    @Transactional
    public void ensureOwnerParticipant(DropSession session, User owner) {
        session.assertUsable();

        participantRepository.findByDropSessionAndUser(session, owner)
                .orElseGet(() -> {
                    DropSessionParticipant p = new DropSessionParticipant();
                    p.setDropSession(session);
                    p.setUser(owner);
                    p.setRole(ParticipantRole.OWNER);
                    return participantRepository.save(p);
                });
    }

    @Transactional
    public void leave(DropSession session, User user){
        participantRepository.findByDropSessionAndUser(session, user)
                .ifPresent(participant -> {
                    UUID pid = participant.getId();
                    noteRepository.deleteBySender(participant);
                    participantRepository.delete(participant);

                    PeerLeftPayload payload = new PeerLeftPayload(pid.toString(), user.getId().toString());

                    webSocketNotifier.sendEventAfterCommit(session.getId().toString(), MessageType.PEER_LEFT, payload);
                    log.info("User {} left session {}", user.getId(), session.getId());
                });
    }
    
    public ParticipantSettingsResponse getMySettings(DropSession session, User user) {
        session.assertUsable();

        DropSessionParticipant participant = participantRepository
                .findByDropSessionAndUser(session, user)
                .orElseThrow(() -> new ResourceNotFoundException("You are not a participant in this session"));

        return new ParticipantSettingsResponse(participant.isAutoDownload());
    }
    
    @Transactional
    public ParticipantSettingsResponse updateMySettings(DropSession session, User user, ParticipantSettingsRequest request){
        session.assertUsable();

        DropSessionParticipant participant = participantRepository
                .findByDropSessionAndUser(session, user)
                .orElseThrow(() -> new ResourceNotFoundException("You are not a participant in this session"));

        participant.setAutoDownload(request.autoDownload());
        participantRepository.save(participant);

        return new ParticipantSettingsResponse(participant.isAutoDownload());
    }

    public Optional<DropSessionParticipant> findByDropSessionAndUser(DropSession session, User user){
        return participantRepository.findByDropSessionAndUser(session, user);
    }

    @Transactional
    public int cleanupDisconnected(Duration maxDisconnectDuration) {
        Instant cutoff = Instant.now().minus(maxDisconnectDuration);
        List<DropSessionParticipant> toRemove = participantRepository.findByDisconnectedAtBefore(cutoff);

        for (DropSessionParticipant p : toRemove) {
            log.debug("Removing disconnected participant: participantId={} sessionId={}",
                    p.getId(), p.getDropSession().getId());
            participantRepository.delete(p);
        }

        return toRemove.size();
    }

    public List<DropSessionParticipant> getParticipants(DropSession session) {
        return participantRepository.findByDropSession(session);
    }

    public boolean existsByDropSessionAndUser(DropSession session, User user) {
        return participantRepository.existsByDropSessionAndUser(session, user);
    }
}
