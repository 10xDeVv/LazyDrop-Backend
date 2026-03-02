package com.lazydrop.modules.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketNotifier {

    private final SimpMessagingTemplate messagingTemplate;

    public void broadcastToSessionAfterCommit(String sessionId, Object payload) {
        String destination = "/topic/session/" + sessionId;

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        messagingTemplate.convertAndSend(destination, payload);
                    } catch (Exception e) {
                        log.error("Failed to send WS message to {} after commit", destination, e);
                    }
                }
            });
        } else {
            messagingTemplate.convertAndSend(destination, payload);
        }
    }

    public void sendToUserAfterCommit(String userId, String queue, Object payload) {
        String destination = "/user/" + userId + queue;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        messagingTemplate.convertAndSendToUser(userId, queue, payload);
                    } catch (Exception e) {
                        log.error("Failed to send user-specific WS message to {} after commit", destination, e);
                    }
                }
            });
        } else {
            messagingTemplate.convertAndSendToUser(userId, queue, payload);
        }
    }

    public <T> void sendEventAfterCommit(String sessionId, MessageType type, T payload) {
        WebSocketMessage<T> msg = new WebSocketMessage<>(type, payload);
        broadcastToSessionAfterCommit(sessionId, msg);
    }
}
