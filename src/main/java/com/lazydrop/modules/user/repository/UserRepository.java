package com.lazydrop.modules.user.repository;

import com.lazydrop.modules.user.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository  extends JpaRepository<User, UUID> {
    Optional<User> findBySupabaseUserId(UUID supabaseUserId);
    
    Optional<User> findByGuestId(String guestId);

    Optional<User> findByEmail(String mail);
}
