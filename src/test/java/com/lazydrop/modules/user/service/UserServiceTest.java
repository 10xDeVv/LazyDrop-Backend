package com.lazydrop.modules.user.service;

import com.lazydrop.modules.user.model.User;
import com.lazydrop.modules.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService Tests")
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(UUID.randomUUID())
                .email("test@example.com")
                .supabaseUserId(UUID.randomUUID())
                .guest(false)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("Should find user by Supabase ID")
    void testFindBySupabaseUserId() {
        // Arrange
        when(userRepository.findBySupabaseUserId(testUser.getSupabaseUserId()))
                .thenReturn(Optional.of(testUser));

        // Act
        Optional<User> result = userService.findBySupabaseUserId(testUser.getSupabaseUserId());

        // Assert
        assertThat(result)
                .isPresent()
                .contains(testUser);
        verify(userRepository).findBySupabaseUserId(testUser.getSupabaseUserId());
    }

    @Test
    @DisplayName("Should find user by email")
    void testFindByEmail() {
        // Arrange
        when(userRepository.findByEmail("test@example.com"))
                .thenReturn(Optional.of(testUser));

        // Act
        Optional<User> result = userService.findByEmail("test@example.com");

        // Assert
        assertThat(result)
                .isPresent()
                .contains(testUser);
        verify(userRepository).findByEmail("test@example.com");
    }

    @Test
    @DisplayName("Should create new user from Supabase ID")
    void testCreateOrUpdateFromSupabaseId() {
        // Arrange
        UUID supabaseId = UUID.randomUUID();
        when(userRepository.findBySupabaseUserId(supabaseId)).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenReturn(testUser);

        // Act
        User result = userService.getOrCreateUser(supabaseId, "test@example.com");

        // Assert
        assertThat(result).isNotNull();
        verify(userRepository).findBySupabaseUserId(supabaseId);
        verify(userRepository).save(argThat(user ->
                user.getSupabaseUserId().equals(supabaseId) &&
                        user.getEmail().equals("test@example.com") &&
                        !user.isGuest()
        ));
    }

    @Test
    @DisplayName("Should update existing user")
    void testCreateOrUpdateExistingUser() {
        // Arrange
        UUID supabaseId = testUser.getSupabaseUserId();
        when(userRepository.findBySupabaseUserId(supabaseId)).thenReturn(Optional.of(testUser));
        when(userRepository.save(any(User.class))).thenReturn(testUser);

        // Act
        User result = userService.getOrCreateUser(supabaseId, "newemail@example.com");

        // Assert
        assertThat(result).isNotNull();
        verify(userRepository).findBySupabaseUserId(supabaseId);
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("Should identify guest users correctly")
    void testGuestUserIdentification() {
        // Arrange
        User guestUser = User.builder()
                .id(UUID.randomUUID())
                .guest(true)
                .guestId(String.valueOf(UUID.randomUUID()))
                .createdAt(Instant.now())
                .build();

        // Act & Assert
        assertThat(guestUser.isGuest()).isTrue();
        assertThat(testUser.isGuest()).isFalse();
    }

    @Test
    @DisplayName("Should save user successfully")
    void testSaveUser() {
        // Arrange
        when(userRepository.save(testUser)).thenReturn(testUser);

        // Act
        User result = userRepository.save(testUser);

        // Assert
        assertThat(result).isEqualTo(testUser);
        verify(userRepository, times(1)).save(testUser);
    }

    @Test
    @DisplayName("Should find user by ID")
    void testFindById() {
        // Arrange
        when(userRepository.findById(testUser.getId())).thenReturn(Optional.of(testUser));

        // Act
        Optional<User> result = userService.findById(testUser.getId());

        // Assert
        assertThat(result)
                .isPresent()
                .contains(testUser);
        verify(userRepository).findById(testUser.getId());
    }
}