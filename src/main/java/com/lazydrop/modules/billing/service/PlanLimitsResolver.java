package com.lazydrop.modules.billing.service;

import com.lazydrop.modules.subscription.model.PlanLimits;
import com.lazydrop.modules.subscription.model.SubscriptionPlan;
import org.springframework.stereotype.Component;

@Component
public class PlanLimitsResolver {
    
    public PlanLimits getLimitsForPlan(SubscriptionPlan plan){
        return switch (plan) {
            case GUEST -> new PlanLimits(
                    SubscriptionPlan.GUEST,
                    2,              // maxActiveSessions
                    60,             // sessionExpiryMinutes
                    3,              // maxFilesPerSession
                    25L * 1024 * 1024,  // maxFileSizeBytes
                    10,             // maxParticipantsPerSession
                    0,              // maxNotesPerSession
                    0               // maxNoteLength
            );
            case FREE -> new PlanLimits(
                    SubscriptionPlan.FREE,
                    3,              // maxActiveSessions
                    120,            // sessionExpiryMinutes
                    5,              // maxFilesPerSession
                    100L * 1024 * 1024,  // maxFileSizeBytes
                    15,             // maxParticipantsPerSession
                    100,            // maxNotesPerSession
                    300             // maxNoteLength
            );
            case PLUS -> new PlanLimits(
                    SubscriptionPlan.PLUS,
                    10,             // maxActiveSessions
                    120,            // sessionExpiryMinutes
                    50,             // maxFilesPerSession
                    1024L * 1024 * 1024,  // maxFileSizeBytes
                    60,             // maxParticipantsPerSession
                    500,            // maxNotesPerSession
                    500             // maxNoteLength
            );
            case PRO -> new PlanLimits(
                    SubscriptionPlan.PRO,
                    15,             // maxActiveSessions
                    120,            // sessionExpiryMinutes
                    75,             // maxFilesPerSession
                    2048L * 1024 * 1024,  // maxFileSizeBytes
                    120,            // maxParticipantsPerSession
                    2000,           // maxNotesPerSession
                    500             // maxNoteLength
            );
            default -> throw new IllegalArgumentException("Unknown SubscriptionPlan: " + plan);
        };
    }
}
