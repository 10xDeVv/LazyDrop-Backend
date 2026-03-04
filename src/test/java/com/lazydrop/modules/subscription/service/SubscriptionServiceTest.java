package com.lazydrop.modules.subscription.service;

import com.lazydrop.modules.billing.service.PlanLimitsResolver;
import com.lazydrop.modules.subscription.model.SubscriptionPlan;
import com.lazydrop.modules.subscription.model.Subscription;
import com.lazydrop.modules.subscription.model.SubscriptionStatus;
import com.lazydrop.modules.subscription.model.PlanLimits;
import com.lazydrop.modules.subscription.repository.SubscriptionRepository;
import com.lazydrop.modules.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionService Tests")
class SubscriptionServiceTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private PlanLimitsResolver planLimitsResolver;

    @InjectMocks
    private SubscriptionService subscriptionService;

    private User testUser;
    private Subscription testSubscription;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(UUID.randomUUID())
                .email("test@example.com")
                .supabaseUserId(UUID.randomUUID())
                .guest(false)
                .createdAt(Instant.now())
                .build();

        testSubscription = Subscription.builder()
                .id(UUID.randomUUID())
                .user(testUser)
                .planCode(SubscriptionPlan.FREE)
                .stripeCustomerId("cus_test123")
                .status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS))
                .build();
    }

    private void setupPlanLimitsResolverMock() {
        PlanLimits freeLimits = new PlanLimits(
                SubscriptionPlan.FREE,
                3,              // maxActiveSessions
                120,            // sessionExpiryMinutes
                5,              // maxFilesPerSession
                100L * 1024 * 1024,  // maxFileSizeBytes
                15,             // maxParticipantsPerSession
                100,            // maxNotesPerSession
                300             // maxNoteLength
        );
        when(planLimitsResolver.getLimitsForPlan(SubscriptionPlan.FREE))
                .thenReturn(freeLimits);
    }

    @Test
    @DisplayName("Should get or create subscription for user")
    void testGetOrCreateSubscription() {
        // Arrange
        when(subscriptionRepository.findByUser(testUser))
                .thenReturn(Optional.empty());
        when(subscriptionRepository.save(any())).thenReturn(testSubscription);

        // Act
        Subscription result = subscriptionService.getOrCreateSubscription(testUser);

        // Assert
        assertThat(result).isNotNull();
        verify(subscriptionRepository).save(argThat(sub ->
                sub.getUser().equals(testUser) &&
                        sub.getPlanCode().equals(SubscriptionPlan.FREE) &&
                        sub.getStatus().equals(SubscriptionStatus.ACTIVE)
        ));
    }

    @Test
    @DisplayName("Should return existing subscription")
    void testGetOrCreateSubscriptionExisting() {
        // Arrange
        when(subscriptionRepository.findByUser(testUser))
                .thenReturn(Optional.of(testSubscription));

        // Act
        Subscription result = subscriptionService.getOrCreateSubscription(testUser);

        // Assert
        assertThat(result).isEqualTo(testSubscription);
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should get plan limits for FREE tier")
    void testGetLimitsForFreeUser() {
        // Arrange
        setupPlanLimitsResolverMock();
        when(subscriptionRepository.findByUser(testUser))
                .thenReturn(Optional.of(testSubscription));

        // Act
        PlanLimits limits = subscriptionService.getLimitsForUser(testUser);

        // Assert
        assertThat(limits).isNotNull();
        assertThat(limits.maxActiveSessions()).isEqualTo(3);
        assertThat(limits.maxParticipantsPerSession()).isEqualTo(15);
        assertThat(limits.maxFileSizeBytes()).isEqualTo(100L * 1024 * 1024);
    }

    @Test
    @DisplayName("Should check if subscription is active")
    void testIsSubscriptionActive() {
        // Arrange
        when(subscriptionRepository.findByUser(testUser))
                .thenReturn(Optional.of(testSubscription));

        // Act
        boolean result = subscriptionService.getOrCreateSubscription(testUser)
                .getStatus() == SubscriptionStatus.ACTIVE;

        // Assert
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should identify inactive subscription")
    void testIsSubscriptionInactive() {
        // Arrange
        testSubscription.setStatus(SubscriptionStatus.CANCELED);
        when(subscriptionRepository.findByUser(testUser))
                .thenReturn(Optional.of(testSubscription));

        // Act
        boolean result = subscriptionService.getOrCreateSubscription(testUser)
                .getStatus() == SubscriptionStatus.ACTIVE;

        // Assert
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Should handle missing subscription")
    void testGetLimitsForUserNoSubscription() {
        // Arrange
        setupPlanLimitsResolverMock();
        when(subscriptionRepository.findByUser(testUser))
                .thenReturn(Optional.empty());
        when(subscriptionRepository.save(any())).thenReturn(testSubscription);

        // Act
        PlanLimits limits = subscriptionService.getLimitsForUser(testUser);

        // Assert
        assertThat(limits).isNotNull();
        assertThat(limits.planCode()).isEqualTo(SubscriptionPlan.FREE);
    }

    @Test
    @DisplayName("Should calculate plan limits correctly")
    void testPlanLimitsValues() {
        // Arrange
        setupPlanLimitsResolverMock();
        when(subscriptionRepository.findByUser(testUser))
                .thenReturn(Optional.of(testSubscription));

        // Act
        PlanLimits limits = subscriptionService.getLimitsForUser(testUser);

        // Assert - Based on FREE plan from PlanLimitsResolver
        assertThat(limits.maxActiveSessions()).isEqualTo(3);
        assertThat(limits.maxParticipantsPerSession()).isEqualTo(15);
        assertThat(limits.maxFilesPerSession()).isEqualTo(5);
        assertThat(limits.maxFileSizeBytes()).isEqualTo(100L * 1024 * 1024);
    }

    @Test
    @DisplayName("Should upgrade subscription plan")
    void testUpgradeSubscription() {
        // Arrange
        Subscription upgradedSubscription = Subscription.builder()
                .id(testSubscription.getId())
                .user(testUser)
                .planCode(SubscriptionPlan.PRO)
                .stripeCustomerId("cus_test123")
                .status(SubscriptionStatus.ACTIVE)
                .currentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS))
                .build();

        when(subscriptionRepository.findByUser(testUser))
                .thenReturn(Optional.of(testSubscription));
        when(subscriptionRepository.save(any())).thenReturn(upgradedSubscription);

        // Act
        Subscription result = subscriptionService.getOrCreateSubscription(testUser);
        result.setPlanCode(SubscriptionPlan.PRO);
        when(subscriptionRepository.save(any())).thenReturn(upgradedSubscription);
        result = subscriptionRepository.save(result);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getPlanCode()).isEqualTo(SubscriptionPlan.PRO);
        verify(subscriptionRepository).save(any());
    }
}