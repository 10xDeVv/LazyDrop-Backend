package com.lazydrop.modules.session.core.service;

import com.lazydrop.common.exception.ForbiddenOperationException;
import com.lazydrop.common.exception.ResourceNotFoundException;
import com.lazydrop.modules.billing.service.PlanEnforcementService;
import com.lazydrop.modules.session.core.dto.DropSessionStatus;
import com.lazydrop.modules.session.core.model.DropSession;
import com.lazydrop.modules.session.core.model.SessionEndReason;
import com.lazydrop.modules.session.core.repository.DropSessionRepository;
import com.lazydrop.modules.session.participant.service.DropSessionParticipantService;
import com.lazydrop.modules.subscription.model.PlanLimits;
import com.lazydrop.modules.subscription.model.SubscriptionPlan;
import com.lazydrop.modules.subscription.service.SubscriptionService;
import com.lazydrop.modules.user.model.User;
import com.lazydrop.modules.websocket.WebSocketNotifier;
import com.lazydrop.utility.CodeUtility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DropSessionService Tests")
class DropSessionServiceTest {

    @Mock
    private DropSessionRepository dropSessionRepository;
    @Mock
    private SubscriptionService subscriptionService;
    @Mock
    private DropSessionParticipantService participantService;
    @Mock
    private PlanEnforcementService planEnforcementService;
    @Mock
    private CodeUtility codeUtility;
    @Mock
    private WebSocketNotifier webSocketNotifier;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    private DropSessionService dropSessionService;

    private User testUser;
    private PlanLimits testLimits;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(UUID.randomUUID())
                .email("test@example.com")
                .supabaseUserId(UUID.randomUUID())
                .guest(false)
                .createdAt(Instant.now())
                .build();

        testLimits = new PlanLimits(
                SubscriptionPlan.FREE,
                3,
                15,
                5,
                100L * 1024 * 1024,
                4,
                100,
                300
        );
    }

    @Test
    @DisplayName("Should create a drop session with correct properties")
    void testCreateDropSession() {
        // Arrange
        when(subscriptionService.getLimitsForUser(testUser)).thenReturn(testLimits);
        when(codeUtility.newAlphaNumericCode(8)).thenReturn("ABC12345");

        DropSession expectedSession = DropSession.builder()
                .id(UUID.randomUUID())
                .owner(testUser)
                .code("ABC12345")
                .status(DropSessionStatus.OPEN)
                .createdAt(Instant.now())
                .build();
        when(dropSessionRepository.save(any(DropSession.class))).thenReturn(expectedSession);

        // Act
        DropSession result = dropSessionService.createDropSession(testUser);

        // Assert
        assertThat(result)
                .isNotNull()
                .satisfies(session -> {
                    assertThat(session.getOwner()).isEqualTo(testUser);
                    assertThat(session.getCode()).isEqualTo("ABC12345");
                    assertThat(session.getStatus()).isEqualTo(DropSessionStatus.OPEN);
                });

        // Verify interactions
        verify(planEnforcementService).checkSessionCreationLimit(testUser);
        verify(subscriptionService).getLimitsForUser(testUser);
        verify(codeUtility).newAlphaNumericCode(8);
        verify(dropSessionRepository, times(2)).save(any());
        verify(participantService).ensureOwnerParticipant(any(), eq(testUser));
    }

    @Test
    @DisplayName("Should fail to create session when plan limit exceeded")
    void testCreateDropSessionPlanLimitExceeded() {
        // Arrange
        doThrow(new RuntimeException("Plan limit exceeded"))
                .when(planEnforcementService).checkSessionCreationLimit(testUser);

        // Act & Assert
        assertThatThrownBy(() -> dropSessionService.createDropSession(testUser))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Plan limit exceeded");

        verify(dropSessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should find session by code")
    void testFindByCode() {
        // Arrange
        DropSession session = DropSession.builder()
                .id(UUID.randomUUID())
                .code("ABC12345")
                .owner(testUser)
                .build();
        when(dropSessionRepository.findByCode("ABC12345")).thenReturn(Optional.of(session));

        // Act
        Optional<DropSession> result = dropSessionService.findByCode("ABC12345");

        // Assert
        assertThat(result)
                .isPresent()
                .contains(session);
    }

    @Test
    @DisplayName("Should return empty when code not found")
    void testFindByCodeNotFound() {
        // Arrange
        when(dropSessionRepository.findByCode("INVALID")).thenReturn(Optional.empty());

        // Act
        Optional<DropSession> result = dropSessionService.findByCode("INVALID");

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should generate QR code URL")
    void testGenerateQrCode() {
        // Arrange
        UUID sessionId = UUID.randomUUID();
        DropSession session = DropSession.builder()
                .id(sessionId)
                .code("ABC12345")
                .build();
        when(dropSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        // Act
        String qrCode = dropSessionService.generateQrCode(sessionId);

        // Assert
        assertThat(qrCode)
                .isNotBlank()
                .contains("join")
                .contains("ABC12345");
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException for non-existent session QR")
    void testGenerateQrCodeNotFound() {
        // Arrange
        UUID sessionId = UUID.randomUUID();
        when(dropSessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> dropSessionService.generateQrCode(sessionId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Should close session as owner")
    void testCloseSessionAsOwner() {
        // Arrange
        UUID sessionId = UUID.randomUUID();
        DropSession session = DropSession.builder()
                .id(sessionId)
                .owner(testUser)
                .status(DropSessionStatus.OPEN)
                .build();

        when(dropSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        // Act
        dropSessionService.closeSessionById(sessionId, testUser);

        // Assert
        verify(dropSessionRepository).save(argThat(s -> 
                !s.isUsable() && s.getId().equals(sessionId)));
    }

    @Test
    @DisplayName("Should prevent non-owner from closing session")
    void testCloseSessionAsNonOwner() {
        // Arrange
        UUID sessionId = UUID.randomUUID();
        User otherUser = User.builder()
                .id(UUID.randomUUID())
                .email("other@example.com")
                .build();

        DropSession session = DropSession.builder()
                .id(sessionId)
                .owner(testUser)
                .status(DropSessionStatus.OPEN)
                .build();

        when(dropSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        // Act & Assert
        assertThatThrownBy(() -> dropSessionService.closeSessionById(sessionId, otherUser))
                .isInstanceOf(ForbiddenOperationException.class);

        verify(dropSessionRepository, never()).save(any());
    }
}
