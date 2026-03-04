package com.lazydrop.modules.session.file.service;

import com.lazydrop.common.exception.ForbiddenOperationException;
import com.lazydrop.common.exception.ResourceNotFoundException;
import com.lazydrop.modules.billing.service.PlanEnforcementService;
import com.lazydrop.modules.session.core.event.DropSessionEndedEvent;
import com.lazydrop.modules.session.core.service.DropSessionService;
import com.lazydrop.modules.session.file.dto.DownloadUrlResponse;
import com.lazydrop.modules.session.file.dto.DropFileDto;
import com.lazydrop.modules.session.file.dto.FileConfirmRequest;
import com.lazydrop.modules.session.file.dto.SignedUploadResponse;
import com.lazydrop.modules.session.file.model.DropFileDownload;
import com.lazydrop.modules.session.file.repository.DropFileDownloadRepository;
import com.lazydrop.modules.session.participant.model.DropSessionParticipant;
import com.lazydrop.modules.session.participant.service.DropSessionParticipantService;
import com.lazydrop.modules.subscription.service.SubscriptionService;
import com.lazydrop.modules.session.file.model.DropFile;
import com.lazydrop.modules.session.core.model.DropSession;
import com.lazydrop.modules.session.file.repository.DropFileRepository;
import com.lazydrop.modules.storage.service.StorageService;
import com.lazydrop.modules.user.model.User;
import com.lazydrop.modules.websocket.MessageType;
import com.lazydrop.modules.websocket.WebSocketNotifier;
import com.lazydrop.modules.websocket.payload.DropSessionFilesCleanedPayload;
import com.lazydrop.modules.websocket.payload.FileDownloadedPayload;
import com.lazydrop.modules.websocket.payload.FileUploadedPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DropFileService {

    private final DropSessionService dropSessionService;
    private final DropFileRepository dropFileRepository;
    private final DropFileDownloadRepository dropFileDownloadRepository;
    private final SubscriptionService subscriptionService;
    private final StorageService storageService;
    private final WebSocketNotifier webSocketNotifier;
    private final DropSessionParticipantService dropSessionParticipantService;
    private final PlanEnforcementService planEnforcementService;

    @Transactional(readOnly = true)
    public SignedUploadResponse requestUploadUrl(UUID sessionId, User uploader, String fileName, String contentType, long contentLength, int expiresInSec) {
        DropSession session = dropSessionService.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("DropSession not found"));

        session.assertUsable();

        requireParticipant(session, uploader);

        planEnforcementService.checkFileUploadLimits(session, contentLength);

        return storageService.createSignedUploadUrl(sessionId.toString(), fileName, contentType, expiresInSec);
    }

    @Transactional
    public DropFile confirmUpload(UUID sessionId, User uploader, FileConfirmRequest request){
        DropSession session = dropSessionService.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("DropSession with id " + sessionId + " not found"));

        session.assertUsable();

        DropSessionParticipant participant = requireParticipant(session, uploader);

        planEnforcementService.checkFileUploadLimits(session, request.getSizeBytes());

        DropFile file = DropFile.builder()
                .dropSession(session)
                .uploader(uploader)
                .storagePath(request.getObjectPath())
                .originalName(request.getOriginalName())
                .sizeBytes(request.getSizeBytes())
                .createdAt(Instant.now())
                .build();

        file = dropFileRepository.save(file);

        FileUploadedPayload payload = new FileUploadedPayload(
                file.getId().toString(),
                file.getOriginalName(),
                file.getSizeBytes(),
                file.getUploader().getId().toString(),
                participant.getId().toString(),
                file.getCreatedAt()
        );

        webSocketNotifier.sendEventAfterCommit(sessionId.toString(), MessageType.FILE_UPLOADED, payload);

        return file;
    }

    @Transactional(readOnly = true)
    public DownloadUrlResponse getDownloadUrl(UUID sessionId, UUID fileId, User downloader, int expiresInSec) {
        DropSession session = dropSessionService.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("DropSession with id " + sessionId + " not found"));

        session.assertUsable();
        requireParticipant(session, downloader);

        DropFile file = dropFileRepository.findById(fileId)
                .orElseThrow(() -> new ResourceNotFoundException("File not found"));

        if (!file.getDropSession().getId().equals(sessionId)) {
            throw new ResourceNotFoundException("File not found in this session");
        }

        String signedUrl = storageService.createSignedDownloadUrl(file.getStoragePath(), expiresInSec);

        return DownloadUrlResponse.builder()
                .downloadUrl(signedUrl)
                .expiresIn(expiresInSec)
                .fileName(file.getOriginalName())
                .build();
    }

    @Transactional
    public void markFileAsDownloaded(UUID sessionId, UUID fileId, User downloader){
        DropSession session = dropSessionService.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("DropSession with id " + sessionId + " not found"));

        session.assertUsable();

        DropFile file = dropFileRepository.findById(fileId)
                .orElseThrow(() -> new ResourceNotFoundException("File not found"));

        if (!file.getDropSession().getId().equals(sessionId)) {
            throw new ResourceNotFoundException("File not found in this session");
        }

        DropSessionParticipant participant = requireParticipant(session, downloader);

        Instant now = Instant.now();

        if (!dropFileDownloadRepository.existsByParticipantAndFile(participant, file)) {
            DropFileDownload download = DropFileDownload.builder()
                    .file(file)
                    .participant(participant)
                    .downloadedAt(now)
                    .build();
            dropFileDownloadRepository.save(download);
        }

        FileDownloadedPayload payload = new FileDownloadedPayload(
                file.getId().toString(),
                participant.getId().toString(),
                now
        );

        webSocketNotifier.sendEventAfterCommit(sessionId.toString(), MessageType.FILE_DOWNLOADED, payload);
    }

    @Transactional(readOnly = true)
    public List<DropFileDto> getAllFiles(UUID sessionId, User user){
        DropSession session = dropSessionService.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("DropSession with id " + sessionId + " not found"));

        session.assertUsable();

        DropSessionParticipant participant = requireParticipant(session, user);

        var downloadedIds = dropFileDownloadRepository.findDownloadedFileIds(participant, session);

        return dropFileRepository.findByDropSession(session)
                .stream()
                .map(f -> DropFileDto.builder()
                        .id(f.getId().toString())
                        .originalName(f.getOriginalName())
                        .sizeBytes(f.getSizeBytes())
                        .createdAt(f.getCreatedAt())
                        .downloadedByMe(downloadedIds.contains(f.getId()))
                        .build())
                .toList();
    }

    @Async
    @EventListener
    @Transactional
    public void handleSessionEnded(DropSessionEndedEvent event) {
        UUID sessionId = event.getSessionId();
        DropSession session = dropSessionService.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("DropSession with id " + sessionId + " not found"));

        List<DropFile> files = dropFileRepository.findByDropSession(session);

        long totalBytes = 0;
        int fileCount = 0;

        for(DropFile file : files){
            try {
                storageService.deleteFile(file.getStoragePath());
                totalBytes += file.getSizeBytes();
                fileCount++;
                log.info("Deleted file: fileId={} name={} size={}B from sessionId={}",
                        file.getId(),
                        file.getOriginalName(),
                        file.getSizeBytes(),
                        sessionId);
            } catch (Exception e) {
                log.warn("Failed to delete file {} from storage", file.getStoragePath(), e);
            }
        }

        dropFileRepository.deleteByDropSession(session);

        DropSessionFilesCleanedPayload payload = new DropSessionFilesCleanedPayload(
                sessionId.toString(),
                fileCount,
                totalBytes
        );

        webSocketNotifier.sendEventAfterCommit(sessionId.toString(),
                MessageType.DROP_SESSION_FILES_CLEANED,
                payload);

        log.info("Cleaned {} files ({} bytes) for session {} ended by {}",
                fileCount, totalBytes, sessionId, event.getReason());
    }

    private DropSessionParticipant requireParticipant(DropSession session, User user) {
       return dropSessionParticipantService.findByDropSessionAndUser(session, user)
                .orElseThrow(() -> new ForbiddenOperationException("You must join the session"));
    }
}
