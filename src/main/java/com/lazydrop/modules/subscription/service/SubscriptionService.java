package com.lazydrop.modules.subscription.service;

import com.lazydrop.common.exception.ForbiddenOperationException;
import com.lazydrop.modules.billing.service.PlanLimitsResolver;
import com.lazydrop.modules.subscription.model.PlanLimits;
import com.lazydrop.modules.subscription.model.Subscription;
import com.lazydrop.modules.subscription.model.SubscriptionPlan;
import com.lazydrop.modules.subscription.model.SubscriptionStatus;
import com.lazydrop.modules.subscription.repository.SubscriptionRepository;
import com.lazydrop.modules.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@RequiredArgsConstructor
@Service
public class SubscriptionService {
    private final PlanLimitsResolver planLimitsResolver;
    private final SubscriptionRepository subscriptionRepository;

    @Transactional
    public Subscription getOrCreateSubscription(User user){
        if (user.isGuest()) {
            throw new ForbiddenOperationException("Guests do not have subscriptions.");
        }

        return subscriptionRepository.findByUser(user)
                .orElseGet(() -> createFreeSubscriptionIdempotent(user));
    }

    private Subscription createFreeSubscriptionIdempotent(User user) {
        try {
            Subscription sub = Subscription.builder()
                    .user(user)
                    .planCode(SubscriptionPlan.FREE)
                    .status(SubscriptionStatus.ACTIVE)
                    .build();
            return subscriptionRepository.save(sub);
        } catch (DataIntegrityViolationException e) {
            return subscriptionRepository.findByUser(user)
                    .orElseThrow(() -> e);
        }
    }

    @Transactional
    public PlanLimits getLimitsForUser(User user){
        if (user.isGuest()) {
            return planLimitsResolver.getLimitsForPlan(SubscriptionPlan.GUEST);
        }

        Subscription subscription = subscriptionRepository
                .findByUser(user)
                .orElseGet(() -> createFreeSubscriptionIdempotent(user));

        return planLimitsResolver.getLimitsForPlan(subscription.getPlanCode());
    }

    @Transactional
    public void activateSubscription(User user, String stripeSubscriptionId,
                                     SubscriptionPlan plan, Instant periodEnd) {
        Subscription subscription = getOrCreateSubscription(user);
        subscription.setStripeSubscriptionId(stripeSubscriptionId);
        subscription.setPlanCode(plan);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setCurrentPeriodEnd(periodEnd);
        subscription.setCancelAtPeriodEnd(false);
        subscriptionRepository.save(subscription);
    }

    @Transactional
    public void renewSubscription(User user, Instant newPeriodEnd) {
        Subscription subscription = getOrCreateSubscription(user);
        subscription.setCurrentPeriodEnd(newPeriodEnd);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscriptionRepository.save(subscription);
    }

    @Transactional
    public void markPaymentFailed(User user) {
        Subscription subscription = getOrCreateSubscription(user);
        subscription.setStatus(SubscriptionStatus.PAST_DUE);
        subscriptionRepository.save(subscription);
    }

    @Transactional
    public Subscription updateSubscription(Subscription subscription){
        return subscriptionRepository.save(subscription);
    }

    public Optional<Subscription> findByStripeSubscriptionId(String stripeSubscriptionId) {
        return subscriptionRepository.findByStripeSubscriptionId(stripeSubscriptionId);
    }

    public Optional<Subscription> findByStripeCustomerId(String stripeCustomerId) {
        return subscriptionRepository.findByStripeCustomerId(stripeCustomerId);
    }
}
