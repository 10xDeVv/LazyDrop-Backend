package com.lazydrop.modules.session.core.mapper;

import com.lazydrop.modules.session.core.dto.DropSessionResponse;
import com.lazydrop.modules.session.core.model.DropSession;
import com.lazydrop.modules.user.model.User;

import java.time.Instant;

public class DropSessionMapper {

    public static DropSessionResponse toDropSessionResponse(DropSession session, User me) {
        return toDropSessionResponse(session, me, null, 0, 0);
    }

    public static DropSessionResponse toDropSessionResponse(
            DropSession session, User me, String joinBaseUrl,
            int participantCount, long fileCount) {
        boolean isOwner = session.getOwner().getId().equals(me.getId());
        long remainingSeconds = 0;
        if (session.getExpiresAt() != null) {
            remainingSeconds = session.getExpiresAt().getEpochSecond() - Instant.now().getEpochSecond();
        }

        String code = session.getCode();
        String codeDisplay = code != null && code.length() == 8
                ? code.substring(0, 4) + "-" + code.substring(4)
                : code;

        String qrCodeData = null;
        if (joinBaseUrl != null && code != null) {
            qrCodeData = String.format(joinBaseUrl, code);
        }

        return DropSessionResponse.builder()
                .code(code)
                .id(session.getId().toString())
                .codeDisplay(codeDisplay)
                .ownerId(session.getOwner().getId().toString())
                .expiresAt(session.getExpiresAt())
                .remainingSeconds(remainingSeconds)
                .status(session.getStatus())
                .myRole(isOwner ? "OWNER" : "PEER")
                .qrCodeData(qrCodeData)
                .participantCount(participantCount)
                .fileCount(fileCount)
                .build();
    }
}
