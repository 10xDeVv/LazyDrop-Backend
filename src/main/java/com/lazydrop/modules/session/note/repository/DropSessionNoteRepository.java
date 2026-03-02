package com.lazydrop.modules.session.note.repository;

import com.lazydrop.modules.session.note.model.DropSessionNote;
import com.lazydrop.modules.session.participant.model.DropSessionParticipant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.List;
import java.util.UUID;

public interface DropSessionNoteRepository extends JpaRepository<DropSessionNote, UUID> {
    long countBySession_Id(UUID sessionId);

    List<DropSessionNote> findBySession_IdOrderByCreatedAtDesc(UUID sessionId, Pageable pageable);
    
    long deleteBySession_Id(UUID sessionId);

    @Modifying
    void deleteBySender(DropSessionParticipant sender);
}
