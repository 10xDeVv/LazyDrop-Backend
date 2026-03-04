package com.lazydrop.modules.session.file.controller;


import com.lazydrop.auth.IdentityResolver;
import com.lazydrop.modules.session.file.dto.*;
import com.lazydrop.modules.session.file.mapper.DropFileMapper;
import com.lazydrop.security.UserPrincipal;
import com.lazydrop.modules.session.file.service.DropFileService;
import com.lazydrop.modules.user.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/sessions/{sessionId}/files")
public class DropFileController{

    private final DropFileService dropFileService;
    private final IdentityResolver identityResolver;


    @PostMapping("/upload-url")
    public ResponseEntity<SignedUploadResponse> getSignedUploadUrl(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @Valid @RequestBody FileUploadRequest request,
            HttpServletRequest req,
            HttpServletResponse response
            ) {
        User uploader = identityResolver.resolve(userPrincipal, req, response);
        SignedUploadResponse resp = dropFileService.requestUploadUrl(sessionId, uploader, request.getFileName(), request.getContentType(), request.getFileSize(),  300);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/confirm")
    public ResponseEntity<DropFileDto> confirmUpload(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @RequestBody @Valid FileConfirmRequest request,
            HttpServletRequest req,
            HttpServletResponse response
    ) {
        User uploader = identityResolver.resolve(userPrincipal, req, response);
        var file = dropFileService.confirmUpload(sessionId, uploader, request);

        return ResponseEntity.status(HttpStatus.CREATED).body(DropFileMapper.toDropFileDto(file));
    }

    @GetMapping("/{fileId}/download")
    public ResponseEntity<DownloadUrlResponse> getDownloadUrl(
            @PathVariable UUID sessionId,
            @PathVariable UUID fileId,
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            HttpServletRequest req,
            HttpServletResponse response
    ) {
        User requester = identityResolver.resolve(userPrincipal, req, response);
        DownloadUrlResponse resp = dropFileService.getDownloadUrl(sessionId, fileId, requester, 3600);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/{fileId}/mark-downloaded")
    public ResponseEntity<Void> markDownloaded(
            @PathVariable UUID sessionId,
            @PathVariable UUID fileId,
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            HttpServletRequest req,
            HttpServletResponse response
    ) {
        User downloader = identityResolver.resolve(userPrincipal, req, response);
        dropFileService.markFileAsDownloaded(sessionId, fileId, downloader);
        return ResponseEntity.ok().build();
    }

    @GetMapping()
    public ResponseEntity<List<DropFileDto>> getAllFiles(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            HttpServletRequest req,
            HttpServletResponse response
    ){
        User requester = identityResolver.resolve(userPrincipal, req, response);
        List<DropFileDto> files = dropFileService.getAllFiles(sessionId, requester);
        return ResponseEntity.ok(files);
    }
}
