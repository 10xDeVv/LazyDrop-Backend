package com.lazydrop.modules.session.core.controller;

import com.lazydrop.auth.IdentityResolver;
import com.lazydrop.common.exception.ResourceNotFoundException;
import com.lazydrop.modules.session.core.mapper.DropSessionMapper;
import com.lazydrop.modules.session.file.repository.DropFileRepository;
import com.lazydrop.modules.session.participant.repository.DropSessionParticipantRepository;
import com.lazydrop.security.UserPrincipal;
import com.lazydrop.modules.session.core.dto.DropSessionResponse;
import com.lazydrop.modules.session.core.model.DropSession;
import com.lazydrop.modules.session.core.service.DropSessionService;
import com.lazydrop.modules.user.model.User;
import jakarta.annotation.Nullable;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/sessions")
public class DropSessionController{

    private final DropSessionService dropSessionService;
    private final IdentityResolver identityResolver;
    private final DropSessionParticipantRepository participantRepository;
    private final DropFileRepository dropFileRepository;

    @Value("${app.join.base.url}")
    private String joinBaseUrl;

    private DropSessionResponse mapSession(DropSession session, User user) {
        int participantCount = (int) participantRepository.countByDropSession(session);
        long fileCount = dropFileRepository.countByDropSession(session);
        return DropSessionMapper.toDropSessionResponse(session, user, joinBaseUrl, participantCount, fileCount);
    }

    @PostMapping
    public ResponseEntity<DropSessionResponse> createDropSession(@AuthenticationPrincipal @Nullable UserPrincipal userPrincipal, HttpServletRequest req,
                                                                 HttpServletResponse response){
        User owner = identityResolver.resolve(userPrincipal, req, response);
        DropSession resp = dropSessionService.createDropSession(owner);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapSession(resp, owner));
    }

    @GetMapping("/code/{code}")
    public ResponseEntity<DropSessionResponse> getByCode(@PathVariable String code,  @AuthenticationPrincipal @Nullable UserPrincipal userPrincipal,
                                                         HttpServletRequest req,
                                                         HttpServletResponse res){
        DropSession session = dropSessionService.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("Session not found"));

        User user = identityResolver.resolve(userPrincipal, req, res);

        return ResponseEntity.ok(mapSession(session, user));
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<DropSessionResponse> getBySessionId(@PathVariable UUID sessionId,  @AuthenticationPrincipal @Nullable UserPrincipal userPrincipal,
                                                              HttpServletRequest req,
                                                              HttpServletResponse res){
        DropSession session = dropSessionService.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Session not found"));
        User user = identityResolver.resolve(userPrincipal, req, res);

        return ResponseEntity.ok(mapSession(session, user));
    }

    @GetMapping("/{sessionId}/qr")
    public ResponseEntity<Map<String,String>> getQrCode(@PathVariable UUID sessionId){
        String qrCode = dropSessionService.generateQrCode(sessionId);
        return ResponseEntity.ok(Map.of("qrCode", qrCode));
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> closeSession(@PathVariable UUID sessionId, @AuthenticationPrincipal @Nullable UserPrincipal userPrincipal, HttpServletRequest req,
                                             HttpServletResponse response){
        User requester = identityResolver.resolve(userPrincipal, req, response);

        dropSessionService.closeSessionById(sessionId, requester);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/active")
    public ResponseEntity<Map<String, Object>> getMyActiveSessions(
            @AuthenticationPrincipal @Nullable UserPrincipal userPrincipal,
            HttpServletRequest req,
            HttpServletResponse res
    ) {
        User owner = identityResolver.resolve(userPrincipal, req, res);

        var sessions = dropSessionService.getActiveSessionsForUser(owner);

        var dtos = sessions.stream()
                .map(s -> mapSession(s, owner))
                .toList();

        long ownedActive = sessions.stream().filter(s -> s.getOwner().getId().equals(owner.getId())).count();
        long joinedActive = sessions.size() - ownedActive;

        return ResponseEntity.ok(Map.of(
                "sessions", dtos,
                "counts", Map.of(
                        "ownedActive", ownedActive,
                        "joinedActive", joinedActive
                )
        ));
    }

}
