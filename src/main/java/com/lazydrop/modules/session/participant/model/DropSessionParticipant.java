package com.lazydrop.modules.session.participant.model;

import com.lazydrop.modules.session.core.model.DropSession;
import com.lazydrop.modules.session.participant.dto.ParticipantRole;
import com.lazydrop.modules.user.model.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "drop_session_participants",
       uniqueConstraints = @UniqueConstraint(
               columnNames = {"drop_session_id", "user_id"}
       ),
        indexes = {
            @Index(name = "idx_participant_session", columnList = "drop_session_id"),
                @Index(name = "idx_participant_user", columnList = "user_id"),
                @Index(name = "idx_participant_user_session", columnList = "user_id,drop_session_id")
        }
)
public class DropSessionParticipant {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "drop_session_id", nullable = false)
    private DropSession dropSession;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ParticipantRole role;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "disconnected_at")
    private Instant disconnectedAt;

    @Column(name = "auto_download", nullable = false)
    private boolean autoDownload = false;

    @PrePersist
    protected void onCreate() {
        if (joinedAt == null) {
            joinedAt = Instant.now();
        }
    }
}
