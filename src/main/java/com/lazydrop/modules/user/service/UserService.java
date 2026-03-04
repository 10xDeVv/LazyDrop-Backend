package com.lazydrop.modules.user.service;

import com.lazydrop.modules.user.model.User;
import com.lazydrop.modules.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional
    public User getOrCreateUser(UUID supabaseUserId, String email){
        return userRepository.findBySupabaseUserId(supabaseUserId)
                .map(existing -> updateEmailIfChanged(existing, email))
                .orElseGet(() -> createAuthenticatedUser(supabaseUserId, email));
    }

    @Transactional
    public User createGuestUser(String guestId){
        User guest = User.builder()
                .supabaseUserId(null)
                .email("guest+" + UUID.randomUUID().toString().substring(0, 8) + "@temp.lazydrop.app")
                .guest(true)
                .guestId(guestId)
                .createdAt(Instant.now())
                .build();

        return userRepository.save(guest);
    }

    @Transactional
    public User upgradeGuestToAuthenticated(
            String guestId,
            UUID supabaseUserId,
            String email
    ) {
        User guest = userRepository.findByGuestId(guestId)
                .orElseThrow(() -> new IllegalStateException("Guest not found"));

        guest.setGuest(false);
        guest.setSupabaseUserId(supabaseUserId);
        guest.setEmail(email);
        guest.setGuestId(null);

        return userRepository.save(guest);
    }


    private User updateEmailIfChanged(User user, String email) {
        if (!user.getEmail().equals(email)) {
            user.setEmail(email);
            userRepository.save(user);
        }
        return user;
    }

    private User createAuthenticatedUser(UUID supabaseUserId, String email) {
        User user = User.builder()
                .supabaseUserId(supabaseUserId)
                .email(email)
                .guest(false)
                .build();
        return userRepository.save(user);
    }

    public Optional<User> findByGuestId(String guestId){
        return userRepository.findByGuestId(guestId);
    }

    public Optional<User> findById(UUID userId) {
        return userRepository.findById(userId);
    }

    public Optional<User> findBySupabaseUserId(UUID supabaseUserId) {
        return userRepository.findBySupabaseUserId(supabaseUserId);
    }

    public Optional<User> findByEmail(String mail) {
        return userRepository.findByEmail(mail);
    }
}
