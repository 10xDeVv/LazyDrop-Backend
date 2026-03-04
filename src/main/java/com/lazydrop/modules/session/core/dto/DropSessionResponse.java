package com.lazydrop.modules.session.core.dto;

import lombok.Builder;

import java.time.Instant;


@Builder
public record DropSessionResponse(
        String id,
        String code,
        String codeDisplay,
        DropSessionStatus status,
        Instant expiresAt,
        long remainingSeconds,
        String ownerId,
        String qrCodeData,
        String myRole,
        int participantCount,
        long fileCount
) {}
