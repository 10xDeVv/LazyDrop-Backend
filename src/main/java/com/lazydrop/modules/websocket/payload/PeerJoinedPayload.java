package com.lazydrop.modules.websocket.payload;

public record PeerJoinedPayload(
        String participantId,
        String userId,
        String role,
        String displayName,
        boolean guest
) {}
