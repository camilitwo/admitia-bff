package cl.mtn.admitiabff.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.config.JwtService;
import cl.mtn.admitiabff.config.RsaKeyService;
import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.ActiveSessionRepository;
import cl.mtn.admitiabff.repository.EmailVerificationCodeRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import cl.mtn.admitiabff.service.notification.EmailComposerService;
import cl.mtn.admitiabff.util.JsonSupport;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTemporaryPasswordTest {
    @Mock UserRepository userRepository;
    @Mock ActiveSessionRepository activeSessionRepository;
    @Mock EmailVerificationCodeRepository verificationCodeRepository;
    @Mock JwtService jwtService;
    @Mock TokenService tokenService;
    @Mock RsaKeyService rsaKeyService;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JsonSupport jsonSupport;
    @Mock EmailComposerService emailComposerService;

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = spy(new AuthService(userRepository, activeSessionRepository, verificationCodeRepository,
            jwtService, tokenService, rsaKeyService, passwordEncoder, jsonSupport, emailComposerService));
    }

    @Test
    void federatedStaffCanLoginThroughBffWithActiveTemporaryCredential() {
        UserEntity user = temporaryFirebaseUser(LocalDateTime.now().plusHours(1));
        when(rsaKeyService.decryptIfNeeded(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Temporal9", "temporary-hash")).thenReturn(true);
        doReturn(Map.of("success", true, "token", "bff-token", "user", Map.of(
            "active", true, "mustChangePassword", true)))
            .when(service).issueAuthResponse(any(UserEntity.class), isNull(), isNull(), anyBoolean());

        Map<String, Object> response = service.login(Map.of(
            "email", user.getEmail(),
            "password", "Temporal9",
            "portalType", "STAFF"), null, null);

        assertThat(response.get("success")).isEqualTo(true);
        assertThat(user.isActive()).isTrue();
        assertThat(user.isMustChangePassword()).isTrue();
        verify(userRepository).save(user);
        verify(service).issueAuthResponse(user, null, null, true);
    }

    @Test
    void expiredTemporaryCredentialIsRejectedWithoutIssuingSession() {
        UserEntity user = temporaryFirebaseUser(LocalDateTime.now().minusMinutes(1));
        when(rsaKeyService.decryptIfNeeded(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Temporal9", "temporary-hash")).thenReturn(true);

        assertThatThrownBy(() -> service.login(Map.of(
            "email", user.getEmail(),
            "password", "Temporal9",
            "portalType", "STAFF"), null, null))
            .isInstanceOf(SecurityWorkflowException.class)
            .extracting("code").isEqualTo("TEMPORARY_PASSWORD_EXPIRED");

        assertThat(user.isActive()).isTrue();
        verify(userRepository, never()).save(any());
        verify(service, never()).issueAuthResponse(any(), any(), any(), anyBoolean());
    }

    private static UserEntity temporaryFirebaseUser(LocalDateTime expiresAt) {
        UserEntity user = new UserEntity();
        user.setId(7L);
        user.setFirstName("Directora");
        user.setLastName("Uno");
        user.setEmail("directora@mtn.cl");
        user.setRole(Role.CYCLE_DIRECTOR);
        user.setActive(true);
        user.setFirebaseUid("firebase-uid");
        user.setPasswordHash("FIREBASE_MANAGED");
        user.setMustChangePassword(true);
        user.setTemporaryPasswordHash("temporary-hash");
        user.setTemporaryPasswordExpiresAt(expiresAt);
        return user;
    }
}
