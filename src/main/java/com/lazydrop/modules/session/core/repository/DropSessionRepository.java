package com.lazydrop.modules.session.core.repository;

import com.lazydrop.modules.session.core.model.DropSession;
import com.lazydrop.modules.session.core.dto.DropSessionStatus;
import com.lazydrop.modules.user.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DropSessionRepository extends JpaRepository<DropSession, UUID> {

    Optional<DropSession> findByCode(String code);

    long countByOwnerAndStatusInAndExpiresAtAfter(User owner, Collection<DropSessionStatus> statuses, Instant expiresAtAfter);

    List<DropSession> findByStatusInAndExpiresAtBefore(List<DropSessionStatus> open, Instant now);

    List<DropSession> findByOwnerAndStatusInAndExpiresAtAfter(
            User owner,
            List<DropSessionStatus> statuses,
            Instant now
    );

    @Query("""
    select distinct s
    from DropSession s
    left join DropSessionParticipant p
      on p.dropSession = s and p.user = :user
    where s.expiresAt > :now
      and s.status in :statuses
      and (s.owner = :user or p.id is not null)
  """)
    List<DropSession> findActiveForUser(
            @Param("user") User user,
            @Param("statuses") List<DropSessionStatus> statuses,
            @Param("now") Instant now
    );

    List<DropSession> findByOwnerAndStatusIn(User testUser, List<DropSessionStatus> open);
}
