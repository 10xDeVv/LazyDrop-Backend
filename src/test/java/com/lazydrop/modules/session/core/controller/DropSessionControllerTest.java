package com.lazydrop.modules.session.core.controller;

import com.lazydrop.auth.IdentityResolver;
import com.lazydrop.modules.session.core.model.DropSession;
import com.lazydrop.modules.session.core.service.DropSessionService;
import com.lazydrop.modules.session.file.repository.DropFileRepository;
import com.lazydrop.modules.session.participant.repository.DropSessionParticipantRepository;
import com.lazydrop.security.UserPrincipal;
import com.lazydrop.modules.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DropSessionController.class)
@TestPropertySource(properties = "app.join.base.url=http://localhost:3000/join?code=%s")
@DisplayName("DropSessionController Tests")
class DropSessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DropSessionService dropSessionService;

    @MockitoBean
    private IdentityResolver identityResolver;

    @MockitoBean
    private DropSessionParticipantRepository participantRepository;

    @MockitoBean
    private DropFileRepository dropFileRepository;

    private User testUser;
    private DropSession testSession;
    private UserPrincipal userPrincipal;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(UUID.randomUUID())
                .email("test@example.com")
                .supabaseUserId(UUID.randomUUID())
                .guest(false)
                .createdAt(Instant.now())
                .build();

        testSession = DropSession.builder()
                .id(UUID.randomUUID())
                .code("ABC12345")
                .owner(testUser)
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        userPrincipal = new UserPrincipal(testUser.getId(), testUser.getEmail());
    }

    @Test
    @DisplayName("POST /sessions - Should create session successfully")
    @WithMockUser(username = "test@example.com")
    void testCreateSession() throws Exception {
        // Arrange
        when(identityResolver.resolve(any(), any(), any())).thenReturn(testUser);
        when(dropSessionService.createDropSession(testUser)).thenReturn(testSession);

        // Act & Assert
        mockMvc.perform(post("/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .with(user(userPrincipal)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.code").value("ABC12345"))
                .andExpect(jsonPath("$.ownerId").value(testUser.getId().toString()));

        verify(dropSessionService).createDropSession(testUser);
    }

    @Test
    @DisplayName("GET /sessions/code/{code} - Should retrieve session by code")
    void testGetSessionByCode() throws Exception {
        // Arrange
        when(identityResolver.resolve(any(), any(), any())).thenReturn(testUser);
        when(dropSessionService.findByCode("ABC12345")).thenReturn(Optional.of(testSession));

        // Act & Assert
        mockMvc.perform(get("/sessions/code/ABC12345")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ABC12345"));

        verify(dropSessionService).findByCode("ABC12345");
    }

    @Test
    @DisplayName("GET /sessions/code/{code} - Should return 404 for non-existent code")
    void testGetSessionByCodeNotFound() throws Exception {
        // Arrange
        when(identityResolver.resolve(any(), any(), any())).thenReturn(testUser);
        when(dropSessionService.findByCode("INVALID")).thenReturn(Optional.empty());

        // Act & Assert
        mockMvc.perform(get("/sessions/code/INVALID")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /sessions/{sessionId} - Should retrieve session by ID")
    void testGetSessionById() throws Exception {
        // Arrange
        when(identityResolver.resolve(any(), any(), any())).thenReturn(testUser);
        when(dropSessionService.findById(testSession.getId())).thenReturn(Optional.of(testSession));

        // Act & Assert
        mockMvc.perform(get("/sessions/" + testSession.getId())
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(testSession.getId().toString()));

        verify(dropSessionService).findById(testSession.getId());
    }

    @Test
    @DisplayName("GET /sessions/{sessionId}/qr - Should generate QR code")
    void testGetQrCode() throws Exception {
        // Arrange
        when(dropSessionService.generateQrCode(testSession.getId()))
                .thenReturn("http://localhost:3000/join?code=ABC12345");

        // Act & Assert
        mockMvc.perform(get("/sessions/" + testSession.getId() + "/qr")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qrCode").value("http://localhost:3000/join?code=ABC12345"));

        verify(dropSessionService).generateQrCode(testSession.getId());
    }

    @Test
    @DisplayName("DELETE /sessions/{sessionId} - Should close session as owner")
    @WithMockUser(username = "test@example.com")
    void testCloseSession() throws Exception {
        // Arrange
        when(identityResolver.resolve(any(), any(), any())).thenReturn(testUser);

        // Act & Assert
        mockMvc.perform(delete("/sessions/" + testSession.getId())
                .with(user(userPrincipal)))
                .andExpect(status().isNoContent());

        verify(dropSessionService).closeSessionById(testSession.getId(), testUser);
    }

    @Test
    @DisplayName("DELETE /sessions/{sessionId} - Should return 403 for non-owner")
    @WithMockUser(username = "other@example.com")
    void testCloseSessionNonOwner() throws Exception {
        // Arrange
        User otherUser = User.builder()
                .id(UUID.randomUUID())
                .email("other@example.com")
                .build();
        when(identityResolver.resolve(any(), any(), any())).thenReturn(otherUser);
        doThrow(new RuntimeException("Forbidden"))
                .when(dropSessionService).closeSessionById(any(), any());

        // Act & Assert
        mockMvc.perform(delete("/sessions/" + testSession.getId()))
                .andExpect(status().is5xxServerError());
    }
}
