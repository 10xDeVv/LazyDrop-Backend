package com.lazydrop.modules.billing.service;

import com.lazydrop.config.StripeConfig;
import com.lazydrop.modules.billing.model.StripeWebhookEvent;
import com.lazydrop.modules.billing.model.StripeWebhookStatus;
import com.lazydrop.modules.billing.repository.StripeWebhookEventRepository;
import com.lazydrop.modules.subscription.model.SubscriptionPlan;
import com.lazydrop.modules.subscription.model.Subscription;
import com.lazydrop.modules.subscription.model.SubscriptionStatus;
import com.lazydrop.modules.subscription.service.SubscriptionService;
import com.lazydrop.modules.user.model.User;
import com.lazydrop.modules.user.service.UserService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentWebhookService {

    private static final int MAX_ATTEMPTS = 10;
    private static final int BATCH_SIZE = 25;
    private static final Duration PROCESSING_LEASE = Duration.ofMinutes(5);


    private final UserService userService;
    private final SubscriptionService subscriptionService;
    private final StripeConfig stripeConfig;
    private final StripeWebhookEventRepository eventRepo;
    private final TransactionTemplate txTemplate;

    public void receiveAndStore(Event event, String payload, String sigHeader) {
        final String eventId = event.getId();

        StripeWebhookEvent row = StripeWebhookEvent.builder()
                .stripeEventId(eventId)
                .type(event.getType())
                .livemode(event.getLivemode())
                .receivedAt(Instant.now())
                .status(StripeWebhookStatus.RECEIVED)
                .attemptCount(0)
                .nextRetryAt(Instant.now())
                .payload(payload)
                .sigHeader(sigHeader)
                .build();

        try {
            eventRepo.save(row);
        } catch (DataIntegrityViolationException dup) {
            log.info("Stripe webhook duplicate: eventId={} type={}. Skipping insert.", eventId, event.getType());
        }
    }

    public int retryFailedNow(int limit){
        int safeLimit = Math.min(Math.max(limit, 1), 200);

        List<StripeWebhookEvent> rows = eventRepo
                .findByStatusAndNextRetryAtLessThanEqualAndAttemptCountLessThanOrderByReceivedAtAsc(
                        StripeWebhookStatus.FAILED,
                        Instant.now(),
                        MAX_ATTEMPTS,
                        PageRequest.of(0, safeLimit)
                );

        int processed = 0;
        for (StripeWebhookEvent row : rows) {
            processRowSafely(row);

            StripeWebhookEvent latest = eventRepo.findByStripeEventId(row.getStripeEventId()).orElseThrow();
            if (latest.getStatus() == StripeWebhookStatus.PROCESSED) processed++;
        }
        return processed;
    }



    @Scheduled(fixedDelay = 10_000)
    public void scheduledProcessReceived() {
        List<StripeWebhookEvent> rows = eventRepo
                .findByStatusAndNextRetryAtLessThanEqualAndAttemptCountLessThanOrderByReceivedAtAsc(
                        StripeWebhookStatus.RECEIVED,
                        Instant.now(),
                        MAX_ATTEMPTS,
                        PageRequest.of(0, BATCH_SIZE)
                );

        for (StripeWebhookEvent row : rows) {
            processRowSafely(row);
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void scheduledRetryFailed() {
        List<StripeWebhookEvent> rows = eventRepo
                .findByStatusAndNextRetryAtLessThanEqualAndAttemptCountLessThanOrderByReceivedAtAsc(
                        StripeWebhookStatus.FAILED,
                        Instant.now(),
                        MAX_ATTEMPTS,
                        PageRequest.of(0, BATCH_SIZE)
                );

        for (StripeWebhookEvent row : rows) {
            processRowSafely(row);
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void scheduledRecoverStuckProcessing() {
        List<StripeWebhookEvent> rows = eventRepo
                .findByStatusAndNextRetryAtLessThanEqualOrderByReceivedAtAsc(
                        StripeWebhookStatus.PROCESSING,
                        Instant.now(),
                        PageRequest.of(0, BATCH_SIZE)
                );

        for (StripeWebhookEvent row : rows) processRowSafely(row);
    }

    private void processRowSafely(StripeWebhookEvent row) {
        String eventId = row.getStripeEventId();

        Instant leaseUntil = Instant.now().plus(PROCESSING_LEASE);
        Boolean claimed = txTemplate.execute(status -> {
            int updated = eventRepo.claimWithLease(
                    eventId,
                    StripeWebhookStatus.PROCESSING,
                    List.of(StripeWebhookStatus.RECEIVED, StripeWebhookStatus.FAILED, StripeWebhookStatus.PROCESSING),
                    leaseUntil
            );
            return updated == 1;
        });

        if (claimed == null || !claimed) return;

        try {
            Event event = reconstructEvent(row);
            txTemplate.execute(status -> {
                tryProcessAndUpdateStatus(eventId, event);
                return null;
            });
        } catch (Exception e) {
            markFailed(eventId, e);
        }
    }


    private Event reconstructEvent(StripeWebhookEvent row) throws SignatureVerificationException {
        return Webhook.constructEvent(
                row.getPayload(),
                row.getSigHeader(),
                stripeConfig.getWebhookSecret()
        );
    }

    private StripeWebhookStatus tryProcessAndUpdateStatus(String eventId, Event event) {
        try {
            log.info("Processing Stripe event: id={}, type={}", eventId, event.getType());
            processEvent(event);
            markProcessed(eventId);
            log.info("Successfully processed Stripe event: id={}", eventId);
            return StripeWebhookStatus.PROCESSED;
        } catch (UnhandledStripeEventException ign) {
            log.debug("Ignoring Stripe event: id={}, reason={}", eventId, ign.getMessage());
            markIgnored(eventId, ign.getMessage());
            return StripeWebhookStatus.IGNORED;
        } catch (Exception e) {
            markFailed(eventId, e);
            return StripeWebhookStatus.FAILED;
        }
    }

    protected void processEvent(Event event) throws StripeException {
        switch (event.getType()) {
            case "checkout.session.completed" -> handleCheckoutCompleted(event);
            case "invoice.payment_succeeded" -> handleInvoicePaymentSucceeded(event);
            case "invoice.payment_failed" -> handleInvoicePaymentFailed(event);
            case "customer.subscription.updated" -> handleSubscriptionUpdated(event);
            case "customer.subscription.deleted" -> handleSubscriptionDeleted(event);
            default -> throw new UnhandledStripeEventException("Unhandled Stripe event type: " + event.getType());
        }
    }

    private void handleInvoicePaymentSucceeded(Event event) throws StripeException {
        Invoice invoice = (Invoice) event.getDataObjectDeserializer().getObject().orElseThrow();
        String subscriptionId = invoice.getLines().getData().getFirst().getSubscription();

        if (subscriptionId == null) return;

        com.stripe.model.Subscription stripeSubscription =
                com.stripe.model.Subscription.retrieve(subscriptionId);

        Subscription userSubscription =
                subscriptionService.findByStripeSubscriptionId(subscriptionId)
                        .orElseThrow(() -> new IllegalStateException("No user found for subscription: " + subscriptionId));

        Instant newPeriodEnd = Instant.ofEpochSecond(
                stripeSubscription.getItems().getData().getFirst().getCurrentPeriodEnd()
        );

        subscriptionService.renewSubscription(userSubscription.getUser(), newPeriodEnd);
        log.info("Renewed subscription for user: {} (StripeSub: {})",
                userSubscription.getUser().getId(), subscriptionId);
    }

    private void handleCheckoutCompleted(Event event) throws StripeException {
        Session session = (Session) event.getDataObjectDeserializer().getObject().orElseThrow();

        String appUserId = session.getMetadata() != null ? session.getMetadata().get("app_user_id") : null;
        String subscriptionId = session.getSubscription();

        if (appUserId == null || subscriptionId == null) {
            throw new IllegalStateException("Missing app_user_id or subscriptionId in checkout.session.completed");
        }

        com.stripe.model.Subscription stripeSubscription =
                com.stripe.model.Subscription.retrieve(subscriptionId);

        User user = userService.findById(UUID.fromString(appUserId))
                .orElseThrow(() -> new IllegalStateException("User not found"));

        String priceId = stripeSubscription.getItems().getData().getFirst().getPrice().getId();
        SubscriptionPlan plan = determinePlanFromPriceId(priceId);

        Instant periodEnd = Instant.ofEpochSecond(
                stripeSubscription.getItems().getData().getFirst().getCurrentPeriodEnd()
        );

        subscriptionService.activateSubscription(user, stripeSubscription.getId(), plan, periodEnd);
        log.info("Activated {} plan for user: {} (StripeSub: {})",
                plan, user.getId(), stripeSubscription.getId());
    }

    private void handleInvoicePaymentFailed(Event event) {
        Invoice invoice = (Invoice) event.getDataObjectDeserializer().getObject().orElseThrow();
        String subscriptionId = invoice.getLines().getData().getFirst().getSubscription();
        if (subscriptionId == null) return;

        subscriptionService.findByStripeSubscriptionId(subscriptionId)
                .ifPresent(subscription -> subscriptionService.markPaymentFailed(subscription.getUser()));
    }

    private void handleSubscriptionUpdated(Event event) {
        com.stripe.model.Subscription stripeSubscription =
                (com.stripe.model.Subscription) event.getDataObjectDeserializer().getObject().orElseThrow();

        Instant periodEnd = Instant.ofEpochSecond(stripeSubscription.getItems().getData().getFirst().getCurrentPeriodEnd());
        boolean canceling = isScheduledToCancel(stripeSubscription);

        String priceId = stripeSubscription.getItems().getData().getFirst().getPrice().getId();
        SubscriptionPlan newPlan = determinePlanFromPriceId(priceId);

        SubscriptionStatus mappedStatus = mapStripeStatus(stripeSubscription);

        String stripeSubId = stripeSubscription.getId();
        String stripeCustomerId = stripeSubscription.getCustomer();

        Subscription sub = subscriptionService.findByStripeSubscriptionId(stripeSubId)
                .orElseGet(() -> subscriptionService.findByStripeCustomerId(stripeCustomerId)
                        .orElseThrow(() -> new IllegalStateException(
                                "No subscription row found for stripeSubId=" + stripeSubId +
                                        " stripeCustomerId=" + stripeCustomerId)));

        if (sub.getStripeSubscriptionId() == null) {
            sub.setStripeSubscriptionId(stripeSubId);
        }

        sub.setPlanCode(newPlan);
        sub.setStatus(mappedStatus);
        sub.setCurrentPeriodEnd(periodEnd);
        sub.setCancelAtPeriodEnd(canceling);

        log.info("SUB_UPDATED stripeSubId={} status={} cancelAtPeriodEnd={} cancelAt={} currentPeriodEnd={}",
                stripeSubId,
                stripeSubscription.getStatus(),
                stripeSubscription.getCancelAtPeriodEnd(),
                stripeSubscription.getCancelAt(),
                stripeSubscription.getItems().getData().getFirst().getCurrentPeriodEnd()
        );


        subscriptionService.updateSubscription(sub);
    }

    private void handleSubscriptionDeleted(Event event) {
        com.stripe.model.Subscription stripeSubscription =
                (com.stripe.model.Subscription) event.getDataObjectDeserializer().getObject().orElseThrow();

        Instant stripePeriodEnd = Instant.ofEpochSecond(stripeSubscription.getItems().getData().getFirst().getCurrentPeriodEnd());

        String stripeSubId = stripeSubscription.getId();
        String stripeCustomerId = stripeSubscription.getCustomer();

        Subscription sub = subscriptionService.findByStripeSubscriptionId(stripeSubId)
                .orElseGet(() -> subscriptionService.findByStripeCustomerId(stripeCustomerId)
                        .orElseThrow(() -> new IllegalStateException(
                                "No subscription found for stripeSubId=" + stripeSubId +
                                        " stripeCustomerId=" + stripeCustomerId)));

        if (sub.getStripeSubscriptionId() == null || !sub.getStripeSubscriptionId().equals(stripeSubId)) {
            sub.setStripeSubscriptionId(stripeSubId);
        }

        sub.setStatus(SubscriptionStatus.CANCELED);
        sub.setCancelAtPeriodEnd(false);
        sub.setCurrentPeriodEnd(stripePeriodEnd);

        if (!stripePeriodEnd.isAfter(Instant.now())) {
            sub.setPlanCode(SubscriptionPlan.FREE);
        }

        subscriptionService.updateSubscription(sub);
    }

    private SubscriptionPlan determinePlanFromPriceId(String priceId) {
        if (priceId == null) return SubscriptionPlan.FREE;
        if (priceId.equals(stripeConfig.getPlusPriceId())) return SubscriptionPlan.PLUS;
        if (priceId.equals(stripeConfig.getProPriceId())) return SubscriptionPlan.PRO;
        return SubscriptionPlan.FREE;
    }

    protected void markProcessed(String eventId) {
        txTemplate.executeWithoutResult(status -> {
            StripeWebhookEvent latest = eventRepo.findByStripeEventId(eventId).orElseThrow();
            int nextAttempt = latest.getAttemptCount() + 1;

            eventRepo.updateStatus(
                    eventId,
                    StripeWebhookStatus.PROCESSED,
                    Instant.now(),
                    nextAttempt,
                    null,
                    null
            );
        });
    }

    protected void markIgnored(String eventId, String reason) {
        txTemplate.executeWithoutResult(status -> {
            StripeWebhookEvent latest = eventRepo.findByStripeEventId(eventId).orElseThrow();
            int nextAttempt = latest.getAttemptCount() + 1;

            eventRepo.updateStatus(
                    eventId,
                    StripeWebhookStatus.IGNORED,
                    Instant.now(),
                    nextAttempt,
                    truncate(reason, 500),
                    null
            );
        });
    }


    private void markFailed(String eventId, Exception ex) {
        txTemplate.executeWithoutResult(status -> {
            StripeWebhookEvent latest = eventRepo.findByStripeEventId(eventId).orElseThrow();
            int nextAttempt = latest.getAttemptCount() + 1;

            String lastError = truncate(ex.getClass().getSimpleName() + ": " + ex.getMessage(), 500);
            Instant nextRetry = (nextAttempt >= MAX_ATTEMPTS) ? null : Instant.now().plus(backoff(nextAttempt));

            eventRepo.updateStatus(
                    eventId,
                    StripeWebhookStatus.FAILED,
                    null,
                    nextAttempt,
                    lastError,
                    nextRetry
            );
        });

        log.warn("Stripe webhook failed: eventId={} err={}", eventId,
                (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
    }


    private Duration backoff(int attempt) {
        long minutes = Math.min(60, (long) Math.pow(2, Math.max(0, attempt - 1)));
        return Duration.ofMinutes(minutes);
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }


    private static class UnhandledStripeEventException extends RuntimeException {
        public UnhandledStripeEventException(String msg) { super(msg); }
    }

    private SubscriptionStatus mapStripeStatus(com.stripe.model.Subscription stripeSub) {
        String s = stripeSub.getStatus();
        if (s == null) return SubscriptionStatus.ACTIVE;

        return switch (s) {
            case "active", "trialing" -> SubscriptionStatus.ACTIVE;
            case "past_due", "unpaid" -> SubscriptionStatus.PAST_DUE;
            case "canceled" -> SubscriptionStatus.CANCELED;
            default -> SubscriptionStatus.ACTIVE;
        };
    }

    private boolean isScheduledToCancel(com.stripe.model.Subscription s) {
        if (Boolean.TRUE.equals(s.getCancelAtPeriodEnd())) return true;

        Long cancelAt = s.getCancelAt();
        return cancelAt != null && cancelAt > Instant.now().getEpochSecond();
    }


}
