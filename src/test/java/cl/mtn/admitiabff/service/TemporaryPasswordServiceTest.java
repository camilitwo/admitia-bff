package cl.mtn.admitiabff.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.config.AuthContext;
import cl.mtn.admitiabff.config.AuthUser;
import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.domain.email.EmailRequestDTO;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.ActiveSessionRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import cl.mtn.admitiabff.service.notification.EmailComposerService;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class TemporaryPasswordServiceTest {
    @Mock UserRepository userRepository;
    @Mock ActiveSessionRepository activeSessionRepository;
    @Mock TokenService tokenService;
    @Mock PasswordEncoder passwordEncoder;
    @Mock FirebaseCredentialService firebaseCredentialService;
    @Mock EmailComposerService emailComposerService;

    private TemporaryPasswordService service;
    private UserEntity admin;

    @BeforeEach
    void setUp() {
        service = new TemporaryPasswordService(userRepository, activeSessionRepository, tokenService,
            passwordEncoder, firebaseCredentialService, emailComposerService, 24, 60,
            "https://admisiones.cmtn.cl");
        admin = user(1L, Role.ADMIN, "admin@cmtn.cl");
        AuthContext.set(new AuthUser(admin.getId(), admin.getEmail(), admin.getRole().name()));
    }

    @AfterEach
    void tearDown() { AuthContext.clear(); }

    @Test
    void resetLocalSendsSensitiveEmailBeforeActivatingCredentialAndNeverReturnsPassword() {
        when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
        UserEntity target = user(2L, Role.TEACHER, "profesor@cmtn.cl");
        target.setPasswordHash("old-hash");
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(passwordEncoder.encode(anyString())).thenReturn("temporary-bcrypt");
        when(emailComposerService.send(any())).thenReturn(Map.of("success", true));
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> result = service.reset(target.getId());

        ArgumentCaptor<EmailRequestDTO> email = ArgumentCaptor.forClass(EmailRequestDTO.class);
        verify(emailComposerService).send(email.capture());
        assertThat(email.getValue().sensitive).isTrue();
        assertThat(email.getValue().templateName).isEqualTo("TEMPORARY_PASSWORD");
        String generatedPassword = String.valueOf(email.getValue().data.get("temporaryPassword"));
        assertThat(generatedPassword).hasSize(16).matches(".*[A-Z].*").matches(".*[a-z].*").matches(".*[0-9].*");
        assertThat(String.valueOf(result)).doesNotContain(String.valueOf(email.getValue().data.get("temporaryPassword")));
        assertThat(target.getPasswordHash()).isEqualTo("temporary-bcrypt");
        assertThat(target.getTemporaryPasswordHash()).isEqualTo("temporary-bcrypt");
        assertThat(target.isMustChangePassword()).isTrue();
        assertThat(target.getTemporaryPasswordExpiresAt()).isAfter(LocalDateTime.now().plusHours(23));
        assertThat(((Map<String, Object>) result.get("data")).keySet())
            .containsExactlyInAnyOrder("email", "expiresAt", "notificationSent");
        verify(tokenService).revokeAllForUser(target, "ADMIN_PASSWORD_RESET");
        verify(activeSessionRepository).deleteByUser(target);
        verify(firebaseCredentialService, never()).updatePassword(anyString(), anyString());
        InOrder order = org.mockito.Mockito.inOrder(emailComposerService, userRepository);
        order.verify(emailComposerService).send(any());
        order.verify(userRepository).save(target);
    }

    @Test
    void resetFirebaseKeepsAccountFederatedAndRevokesProviderSessions() {
        when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
        UserEntity target = user(2L, Role.CYCLE_DIRECTOR, "director@cmtn.cl");
        target.setFirebaseUid(null);
        target.setPasswordHash("FIREBASE_MANAGED");
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(firebaseCredentialService.resolveByEmail(target.getEmail()))
            .thenReturn(new FirebaseCredentialService.ResolvedUser("resolved-firebase-uid", target.getEmail()));
        when(userRepository.findByFirebaseUid("resolved-firebase-uid")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("temporary-bcrypt");
        when(emailComposerService.send(any())).thenReturn(Map.of("success", true));

        service.reset(target.getId());

        ArgumentCaptor<String> password = ArgumentCaptor.forClass(String.class);
        verify(firebaseCredentialService).updatePassword(org.mockito.ArgumentMatchers.eq("resolved-firebase-uid"), password.capture());
        assertThat(password.getValue()).hasSize(16);
        verify(firebaseCredentialService).revokeRefreshTokens("resolved-firebase-uid");
        assertThat(target.getFirebaseUid()).isEqualTo("resolved-firebase-uid");
        assertThat(target.getPasswordHash()).isEqualTo("FIREBASE_MANAGED");
        assertThat(target.isMustChangePassword()).isTrue();
        assertThat(target.isActive()).isTrue();
    }

    @Test
    void emailFailureLeavesExistingCredentialAndSessionsUntouched() {
        when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
        UserEntity target = user(2L, Role.TEACHER, "profesor@cmtn.cl");
        target.setPasswordHash("old-hash");
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(emailComposerService.send(any())).thenThrow(new RuntimeException("Resend unavailable"));

        assertThatThrownBy(() -> service.reset(target.getId())).hasMessageContaining("Resend unavailable");

        assertThat(target.getPasswordHash()).isEqualTo("old-hash");
        assertThat(target.isMustChangePassword()).isFalse();
        verify(firebaseCredentialService, never()).updatePassword(anyString(), anyString());
        verify(tokenService, never()).revokeAllForUser(any(), anyString());
        verify(activeSessionRepository, never()).deleteByUser(any());
    }

    @Test
    void resetRejectsGuardianSelfResetInactiveAndRapidRepeat() {
        when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
        UserEntity guardian = user(2L, Role.APODERADO, "familia@cmtn.cl");
        when(userRepository.findById(guardian.getId())).thenReturn(Optional.of(guardian));
        assertThatThrownBy(() -> service.reset(guardian.getId())).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.reset(admin.getId())).isInstanceOf(IllegalArgumentException.class);

        UserEntity inactive = user(3L, Role.TEACHER, "inactivo@cmtn.cl");
        inactive.setActive(false);
        when(userRepository.findById(inactive.getId())).thenReturn(Optional.of(inactive));
        assertThatThrownBy(() -> service.reset(inactive.getId())).isInstanceOf(IllegalArgumentException.class);

        UserEntity repeated = user(4L, Role.TEACHER, "repetido@cmtn.cl");
        repeated.setPasswordResetAt(LocalDateTime.now().minusSeconds(10));
        when(userRepository.findById(repeated.getId())).thenReturn(Optional.of(repeated));
        assertThatThrownBy(() -> service.reset(repeated.getId()))
            .isInstanceOf(SecurityWorkflowException.class)
            .extracting("code").isEqualTo("PASSWORD_RESET_RATE_LIMITED");
    }

    @Test
    void resetIsRestrictedToAdministrators() {
        UserEntity teacher = user(9L, Role.TEACHER, "teacher@cmtn.cl");
        AuthContext.set(new AuthUser(teacher.getId(), teacher.getEmail(), teacher.getRole().name()));
        when(userRepository.findById(teacher.getId())).thenReturn(Optional.of(teacher));

        assertThatThrownBy(() -> service.reset(2L))
            .isInstanceOf(SecurityWorkflowException.class)
            .extracting("code").isEqualTo("FORBIDDEN");
        verify(emailComposerService, never()).send(any());
    }

    @Test
    void changeLocalPasswordClearsTemporaryStateWithoutRevokingCurrentSession() {
        UserEntity target = user(2L, Role.TEACHER, "profesor@cmtn.cl");
        target.setMustChangePassword(true);
        target.setTemporaryPasswordExpiresAt(LocalDateTime.now().plusHours(1));
        target.setTemporaryPasswordHash("temporary-bcrypt");
        AuthContext.set(new AuthUser(target.getId(), target.getEmail(), target.getRole().name()));
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(passwordEncoder.matches("NuevaClave9", "temporary-bcrypt")).thenReturn(false);
        when(passwordEncoder.encode("NuevaClave9")).thenReturn("definitive-bcrypt");

        Map<String, Object> result = service.change(Map.of("newPassword", "NuevaClave9"));

        assertThat(target.getPasswordHash()).isEqualTo("definitive-bcrypt");
        assertThat(target.isMustChangePassword()).isFalse();
        assertThat(target.getTemporaryPasswordHash()).isNull();
        assertThat(target.getTemporaryPasswordExpiresAt()).isNull();
        assertThat(target.isActive()).isTrue();
        assertThat(((Map<?, ?>) ((Map<?, ?>) result.get("data")).get("user")).get("active")).isEqualTo(true);
        verify(tokenService, never()).revokeAllForUser(any(), anyString());
        verify(activeSessionRepository, never()).deleteByUser(any());
    }

    @Test
    void changeRejectsExpiredWeakAndReusedTemporaryPasswords() {
        UserEntity target = user(2L, Role.TEACHER, "profesor@cmtn.cl");
        target.setMustChangePassword(true);
        target.setTemporaryPasswordExpiresAt(LocalDateTime.now().minusMinutes(1));
        target.setTemporaryPasswordHash("temporary-bcrypt");
        AuthContext.set(new AuthUser(target.getId(), target.getEmail(), target.getRole().name()));
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        assertThatThrownBy(() -> service.change(Map.of("newPassword", "NuevaClave9")))
            .isInstanceOf(SecurityWorkflowException.class)
            .extracting("code").isEqualTo("TEMPORARY_PASSWORD_EXPIRED");

        target.setTemporaryPasswordExpiresAt(LocalDateTime.now().plusHours(1));
        assertThatThrownBy(() -> service.change(Map.of("newPassword", "debil")))
            .isInstanceOf(IllegalArgumentException.class);

        when(passwordEncoder.matches("NuevaClave9", "temporary-bcrypt")).thenReturn(true);
        assertThatThrownBy(() -> service.change(Map.of("newPassword", "NuevaClave9")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("distinta");
    }

    @Test
    void changeFirebasePasswordKeepsFederatedAccountAndCurrentBffSession() {
        UserEntity target = user(2L, Role.PSYCHOLOGIST, "psicologa@cmtn.cl");
        target.setFirebaseUid("firebase-uid");
        target.setPasswordHash("FIREBASE_MANAGED");
        target.setMustChangePassword(true);
        target.setTemporaryPasswordExpiresAt(LocalDateTime.now().plusHours(1));
        target.setTemporaryPasswordHash("temporary-bcrypt");
        AuthContext.set(new AuthUser(target.getId(), target.getEmail(), target.getRole().name()));
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(firebaseCredentialService.resolveByEmail(target.getEmail()))
            .thenReturn(new FirebaseCredentialService.ResolvedUser("resolved-firebase-uid", target.getEmail()));
        when(userRepository.findByFirebaseUid("resolved-firebase-uid")).thenReturn(Optional.empty());
        when(passwordEncoder.matches("Definitiva8", "temporary-bcrypt")).thenReturn(false);

        Map<String, Object> result = service.change(Map.of("newPassword", "Definitiva8"));

        verify(firebaseCredentialService).updatePassword("resolved-firebase-uid", "Definitiva8");
        assertThat(target.getFirebaseUid()).isEqualTo("resolved-firebase-uid");
        assertThat(target.getPasswordHash()).isEqualTo("FIREBASE_MANAGED");
        assertThat(target.isMustChangePassword()).isFalse();
        assertThat(target.isActive()).isTrue();
        assertThat(((Map<?, ?>) ((Map<?, ?>) result.get("data")).get("user")).get("active")).isEqualTo(true);
        verify(tokenService, never()).revokeAllForUser(any(), anyString());
        verify(activeSessionRepository, never()).deleteByUser(any());
    }

    @Test
    void firebaseIdentityFailureStopsBeforeSendingEmailOrChangingAccess() {
        when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
        UserEntity target = user(2L, Role.CYCLE_DIRECTOR, "director@cmtn.cl");
        target.setFirebaseUid("stale-firebase-uid");
        target.setPasswordHash("FIREBASE_MANAGED");
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(firebaseCredentialService.resolveByEmail(target.getEmail()))
            .thenThrow(new IllegalStateException("Firebase identity missing"));

        assertThatThrownBy(() -> service.reset(target.getId()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Firebase identity missing");

        assertThat(target.isActive()).isTrue();
        assertThat(target.isMustChangePassword()).isFalse();
        verify(emailComposerService, never()).send(any());
        verify(firebaseCredentialService, never()).updatePassword(anyString(), anyString());
        verify(tokenService, never()).revokeAllForUser(any(), anyString());
    }

    private static UserEntity user(Long id, Role role, String email) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setFirstName("Nombre");
        user.setLastName("Apellido");
        user.setEmail(email);
        user.setRole(role);
        user.setActive(true);
        user.setPasswordHash("hash");
        return user;
    }
}
