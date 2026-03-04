package com.lazydrop.modules.session.core.service;

import com.lazydrop.modules.session.core.dto.DropSessionStatus;
import com.lazydrop.modules.session.core.model.DropSession;
import com.lazydrop.modules.session.core.repository.DropSessionRepository;
import com.lazydrop.modules.user.model.User;
import com.lazydrop.modules.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Testcontainers
@DisplayName("DropSessionService Integration Tests")
class DropSessionServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("lazydrop_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        
        // Stripe configuration for tests
        registry.add("stripe.secret-key", () -> "sk_test_dummy_secret_key_for_tests");
        registry.add("stripe.webhook-secret", () -> "whsec_test_dummy_webhook_secret");
        registry.add("stripe.prices.pro", () -> "price_test_pro");
        registry.add("stripe.prices.plus", () -> "price_test_plus");
        registry.add("stripe.success-url", () -> "http://localhost:3000/checkout/success");
        registry.add("stripe.cancel-url", () -> "http://localhost:3000/checkout/cancel");
        registry.add("stripe.billing-portal", () -> "http://localhost:3000/account");
        
        // Other required properties
        registry.add("supabase.url", () -> "https://test.supabase.co");
        registry.add("supabase.anon-key", () -> "test_anon_key");
        registry.add("supabase.jwt-secret", () -> "test_jwt_secret");
        registry.add("supabase.service-key", () -> "test_service_key");
        registry.add("supabase.bucket-name", () -> "test_bucket");
        registry.add("cors.allowed-origins", () -> "http://localhost:3000");
        registry.add("app.frontend-url", () -> "http://localhost:3000");
    }

    @Autowired
    private DropSessionRepository dropSessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DropSessionService dropSessionService;

    private User testUser;

    @BeforeEach
    void setUp() {
        // Clean up before each test
        dropSessionRepository.deleteAll();
        userRepository.deleteAll();

        // Create test user
        testUser = User.builder()
                .email("test@integration.com")
                .supabaseUserId(java.util.UUID.randomUUID())
                .guest(false)
                .createdAt(Instant.now())
                .build();
        testUser = userRepository.save(testUser);
    }

    @Test
    @Transactional
    @DisplayName("Should persist session to database with all properties")
    void testCreateSessionPersistence() {
        // Act
        DropSession created = dropSessionService.createDropSession(testUser);

        // Assert - Verify in database
        Optional<DropSession> retrieved = dropSessionRepository.findById(created.getId());
        assertThat(retrieved)
                .isPresent()
                .get()
                .satisfies(session -> {
                    assertThat(session.getCode()).isNotBlank();
                    assertThat(session.getOwner()).isEqualTo(testUser);
                    assertThat(session.getStatus()).isEqualTo(DropSessionStatus.OPEN);
                    assertThat(session.getCreatedAt()).isNotNull();
                    assertThat(session.getExpiresAt()).isAfter(session.getCreatedAt());
                });
    }

    @Test
    @Transactional
    @DisplayName("Should find session by unique code")
    void testFindSessionByCode() {
        // Arrange
        DropSession created = dropSessionService.createDropSession(testUser);

        // Act
        Optional<DropSession> found = dropSessionService.findByCode(created.getCode());

        // Assert
        assertThat(found)
                .isPresent()
                .get()
                .isEqualTo(created);
    }

    @Test
    @DisplayName("Should enforce session code uniqueness")
    void testSessionCodeUniqueness() {
        // Arrange
        DropSession session1 = dropSessionService.createDropSession(testUser);

        User anotherUser = User.builder()
                .email("another@integration.com")
                .supabaseUserId(java.util.UUID.randomUUID())
                .guest(false)
                .createdAt(Instant.now())
                .build();
        anotherUser = userRepository.save(anotherUser);

        // Act - Create another session
        DropSession session2 = dropSessionService.createDropSession(anotherUser);

        // Assert - Codes should be different
        assertThat(session1.getCode()).isNotEqualTo(session2.getCode());
    }

    @Test
    @DisplayName("Should retrieve active sessions for user")
    void testGetUserActiveSessions() {
        // Arrange - Create multiple sessions
        DropSession session1 = dropSessionService.createDropSession(testUser);
        DropSession session2 = dropSessionService.createDropSession(testUser);

        // Act
        java.util.List<DropSession> sessions = dropSessionRepository.findByOwnerAndStatusIn(
                testUser,
                java.util.List.of(DropSessionStatus.OPEN)
        );

        // Assert
        assertThat(sessions)
                .hasSize(2)
                .extracting(DropSession::getId)
                .contains(session1.getId(), session2.getId());
    }

    @Test
    @DisplayName("Should close session and update status in database")
    void testCloseSessionPersistence() {
        // Arrange
        DropSession created = dropSessionService.createDropSession(testUser);

        // Act
        dropSessionService.closeSessionById(created.getId(), testUser);

        // Assert - Verify status change in database
        Optional<DropSession> closed = dropSessionRepository.findById(created.getId());
        assertThat(closed)
                .isPresent()
                .get()
                .satisfies(session -> {
                    assertThat(session.getStatus()).isNotEqualTo(DropSessionStatus.OPEN);
                    assertThat(session.getEndedAt()).isNotNull();
                });
    }

    @Test
    @DisplayName("Should correctly calculate session expiry time")
    void testSessionExpiryCalculation() {
        // Act
        DropSession session = dropSessionService.createDropSession(testUser);

        // Assert
        Instant now = Instant.now();
        long expiryMinutes = java.time.temporal.ChronoUnit.MINUTES.between(now, session.getExpiresAt());
        
        assertThat(expiryMinutes)
                .isGreaterThan(0)
                .isLessThanOrEqualTo(120); // Typical expiry is 60-120 minutes
    }

    @Test
    @Transactional
    @DisplayName("Should maintain data consistency across transactions")
    void testTransactionConsistency() {
        // Act - Create and immediately retrieve
        DropSession created = dropSessionService.createDropSession(testUser);
        Optional<DropSession> retrieved = dropSessionRepository.findById(created.getId());

        // Assert - Data should match exactly
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get())
                .usingRecursiveComparison()
                .isEqualTo(created);
    }
}
