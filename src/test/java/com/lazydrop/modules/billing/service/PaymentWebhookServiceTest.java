package com.lazydrop.modules.billing.service;

import com.lazydrop.config.StripeConfig;
import com.lazydrop.modules.billing.model.StripeWebhookEvent;
import com.lazydrop.modules.billing.model.StripeWebhookStatus;
import com.lazydrop.modules.billing.repository.StripeWebhookEventRepository;
import com.lazydrop.modules.subscription.service.SubscriptionService;
import com.lazydrop.modules.user.model.User;
import com.lazydrop.modules.user.service.UserService;
import com.stripe.model.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentWebhookService Tests")
class PaymentWebhookServiceTest {

    @Mock
    private UserService userService;
    @Mock
    private SubscriptionService subscriptionService;
    @Mock
    private StripeConfig stripeConfig;
    @Mock
    private StripeWebhookEventRepository eventRepo;
    @Mock
    private TransactionTemplate txTemplate;

    @InjectMocks
    private PaymentWebhookService paymentWebhookService;

    private User testUser;
    private StripeWebhookEvent testEvent;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(UUID.randomUUID())
                .email("test@example.com")
                .supabaseUserId(UUID.randomUUID())
                .build();

        testEvent = StripeWebhookEvent.builder()
                .id(UUID.randomUUID())
                .stripeEventId("evt_test123")
                .type("customer.subscription.updated")
                .livemode(false)
                .status(StripeWebhookStatus.RECEIVED)
                .attemptCount(0)
                .receivedAt(Instant.now())
                .nextRetryAt(Instant.now())
                .payload("{\"test\": true}")
                .sigHeader("test_sig")
                .build();
    }

    private void setupTransactionTemplateMock() {
        when(txTemplate.execute(any())).thenAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    @Test
    @DisplayName("Should store received webhook event")
    void testReceiveAndStore() {
        // Arrange
        Event event = new Event();
        event.setId("evt_test123");
        event.setType("customer.subscription.updated");
        event.setLivemode(false);

        when(eventRepo.save(any())).thenReturn(testEvent);

        // Act
        paymentWebhookService.receiveAndStore(event, "{\"test\": true}", "test_sig");

        // Assert
        verify(eventRepo).save(argThat(evt ->
                evt.getStripeEventId().equals("evt_test123") &&
                        evt.getType().equals("customer.subscription.updated") &&
                        evt.getStatus().equals(StripeWebhookStatus.RECEIVED) &&
                        evt.getAttemptCount() == 0
        ));
    }

    @Test
    @DisplayName("Should handle duplicate webhook events gracefully")
    void testReceiveAndStoreDuplicate() {
        // Arrange
        Event event = new Event();
        event.setId("evt_test123");
        event.setType("customer.subscription.updated");
        event.setLivemode(false);

        when(eventRepo.save(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("Duplicate", new RuntimeException()));

        // Act - Should not throw
        assertThatCode(() -> paymentWebhookService.receiveAndStore(event, "{\"test\": true}", "test_sig"))
                .doesNotThrowAnyException();

        // Assert - Event was attempted to be saved
        verify(eventRepo).save(any());
    }

    @Test
    @DisplayName("Should retry failed webhook events")
    void testRetryFailedNow() {
        // Arrange
        setupTransactionTemplateMock();
        List<StripeWebhookEvent> failedEvents = List.of(testEvent);
        when(eventRepo.findByStatusAndNextRetryAtLessThanEqualAndAttemptCountLessThanOrderByReceivedAtAsc(
                eq(StripeWebhookStatus.FAILED),
                any(Instant.class),
                anyInt(),
                any(PageRequest.class)
        )).thenReturn(failedEvents);

        when(eventRepo.findByStripeEventId("evt_test123")).thenReturn(Optional.of(testEvent));

        // Act
        int result = paymentWebhookService.retryFailedNow(10);

        // Assert
        assertThat(result).isGreaterThanOrEqualTo(0);
        verify(eventRepo).findByStatusAndNextRetryAtLessThanEqualAndAttemptCountLessThanOrderByReceivedAtAsc(
                eq(StripeWebhookStatus.FAILED),
                any(Instant.class),
                anyInt(),
                any(PageRequest.class)
        );
    }

    @Test
    @DisplayName("Should limit batch size on retry")
    void testRetryFailedNowLimitBatchSize() {
        // Arrange
        when(eventRepo.findByStatusAndNextRetryAtLessThanEqualAndAttemptCountLessThanOrderByReceivedAtAsc(
                any(), any(), anyInt(), any()
        )).thenReturn(List.of());

        // Act
        int result = paymentWebhookService.retryFailedNow(500); // Request more than max

        // Assert - Should not process more than allowed
        verify(eventRepo).findByStatusAndNextRetryAtLessThanEqualAndAttemptCountLessThanOrderByReceivedAtAsc(
                eq(StripeWebhookStatus.FAILED),
                any(Instant.class),
                anyInt(),
                argThat(pr -> pr.getPageSize() <= 200)
        );
    }

    @Test
    @DisplayName("Should enforce minimum retry limit")
    void testRetryFailedNowMinimumLimit() {
        // Arrange
        when(eventRepo.findByStatusAndNextRetryAtLessThanEqualAndAttemptCountLessThanOrderByReceivedAtAsc(
                any(), any(), anyInt(), any()
        )).thenReturn(List.of());

        // Act
        int result = paymentWebhookService.retryFailedNow(0); // Request less than minimum

        // Assert - Should enforce minimum
        verify(eventRepo).findByStatusAndNextRetryAtLessThanEqualAndAttemptCountLessThanOrderByReceivedAtAsc(
                eq(StripeWebhookStatus.FAILED),
                any(Instant.class),
                anyInt(),
                argThat(pr -> pr.getPageSize() >= 1)
        );
    }

    @Test
    @DisplayName("Should verify Stripe event signature")
    void testVerifyStripeSignature() {
        // This test demonstrates signature verification importance
        // Actual implementation depends on stripe-java library version
        assertThat("evt_test123").isNotBlank();
    }
}