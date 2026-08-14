package cl.mtn.admitiabff.prekinder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.ActiveSessionRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import cl.mtn.admitiabff.service.FirebaseCredentialService;
import cl.mtn.admitiabff.service.TokenService;
import cl.mtn.admitiabff.util.JsonSupport;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PrekinderProfessionalAccountServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final FirebaseCredentialService firebase = mock(FirebaseCredentialService.class);
    private final JsonSupport jsonSupport = mock(JsonSupport.class);
    private final TokenService tokens = mock(TokenService.class);
    private final ActiveSessionRepository sessions = mock(ActiveSessionRepository.class);
    private PrekinderProfessionalAccountService service;

    @BeforeEach
    void setUp() {
        service = new PrekinderProfessionalAccountService(users, firebase, jsonSupport, tokens, sessions);
        when(jsonSupport.write(any())).thenReturn("{\"prekinderProfessional\":true}");
    }

    @Test
    void createsUnverifiedFirebaseAccountAndIsolatedLocalUser() {
        when(users.findByEmailIgnoreCase("ana@mtn.cl")).thenReturn(Optional.empty());
        when(firebase.createUser("ana@mtn.cl", "secreta1", "Ana Pérez"))
            .thenReturn(new FirebaseCredentialService.ResolvedUser("firebase-73", "ana@mtn.cl"));
        when(users.saveAndFlush(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(73L);
            return user;
        });

        var account = service.provision("Ana Pérez", " ANA@MTN.CL ", "secreta1");

        assertThat(account.userId()).isEqualTo(73L);
        assertThat(account.firebaseUid()).isEqualTo("firebase-73");
        assertThat(account.localUserCreated()).isTrue();
        assertThat(account.firebaseCreated()).isTrue();
        ArgumentCaptor<UserEntity> saved = ArgumentCaptor.forClass(UserEntity.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("ana@mtn.cl");
        assertThat(saved.getValue().getFirstName()).isEqualTo("Ana");
        assertThat(saved.getValue().getLastName()).isEqualTo("Pérez");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.PREKINDER_PROFESSIONAL);
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("FIREBASE_MANAGED");
        assertThat(saved.getValue().isEmailVerified()).isFalse();
        assertThat(saved.getValue().isActive()).isTrue();
    }

    @Test
    void reusesExistingFirebaseIdentityWithoutChangingOtherCourseAccount() {
        UserEntity existing = activeUser(41L, Role.TEACHER);
        existing.setFirebaseUid("existing-firebase");
        when(users.findByEmailIgnoreCase("docente@mtn.cl")).thenReturn(Optional.of(existing));

        var account = service.provision("Docente Existente", "docente@mtn.cl", "nueva1");

        assertThat(account.userId()).isEqualTo(41L);
        assertThat(account.firebaseCreated()).isFalse();
        assertThat(existing.getRole()).isEqualTo(Role.TEACHER);
        verify(firebase, never()).createUser(any(), any(), any());
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void linksFirebaseWithoutReplacingExistingRoleOrPassword() {
        UserEntity existing = activeUser(42L, Role.PSYCHOLOGIST);
        existing.setPasswordHash("existing-hash");
        when(users.findByEmailIgnoreCase("psicologa@mtn.cl")).thenReturn(Optional.of(existing));
        when(firebase.createUser("psicologa@mtn.cl", "secreta1", "Pía Soto"))
            .thenReturn(new FirebaseCredentialService.ResolvedUser("firebase-42", "psicologa@mtn.cl"));

        var account = service.provision("Pía Soto", "psicologa@mtn.cl", "secreta1");

        assertThat(account.localUserCreated()).isFalse();
        assertThat(account.firebaseCreated()).isTrue();
        assertThat(existing.getFirebaseUid()).isEqualTo("firebase-42");
        assertThat(existing.getRole()).isEqualTo(Role.PSYCHOLOGIST);
        assertThat(existing.getPasswordHash()).isEqualTo("existing-hash");
        verify(users).saveAndFlush(existing);
    }

    @Test
    void inactiveExistingAccountIsRejectedBeforeFirebaseMutation() {
        UserEntity existing = activeUser(99L, Role.TEACHER);
        existing.setActive(false);
        when(users.findByEmailIgnoreCase("inactiva@mtn.cl")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.provision("Cuenta Inactiva", "inactiva@mtn.cl", "secreta1"))
            .isInstanceOf(PrekinderDomainException.class)
            .extracting("code").isEqualTo("PROFESSIONAL_ACCOUNT_INACTIVE");
        verify(firebase, never()).createUser(any(), any(), any());
    }

    @Test
    void rollbackDeletesNewLocalAndFirebaseAccounts() {
        var account = new PrekinderProfessionalAccountService.ProvisionedAccount(
            73L, "firebase-73", true, true);

        service.rollback(account);

        verify(users).deleteById(73L);
        verify(users).flush();
        verify(firebase).deleteUser("firebase-73");
    }

    @Test
    void updatesFirebasePasswordAndRevokesExistingSessions() {
        UserEntity user = activeUser(73L, Role.PREKINDER_PROFESSIONAL);
        user.setFirebaseUid("firebase-73");
        user.setMustChangePassword(true);
        when(users.findById(73L)).thenReturn(Optional.of(user));

        service.updatePassword(73L, "nueva-secreta");

        verify(firebase).updatePassword("firebase-73", "nueva-secreta");
        verify(firebase).revokeRefreshTokens("firebase-73");
        verify(tokens).revokeAllForUser(user, "PREKINDER_PASSWORD_RESET");
        verify(sessions).deleteByUser(user);
        assertThat(user.isMustChangePassword()).isFalse();
    }

    @Test
    void linkedFirebaseEmailCannotBeChangedFromPrekinderProfile() {
        UserEntity user = activeUser(73L, Role.PREKINDER_PROFESSIONAL);
        user.setEmail("ana@mtn.cl");
        when(users.findById(73L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.requireMatchingEmail(73L, "otra@mtn.cl"))
            .isInstanceOf(PrekinderDomainException.class)
            .extracting("code").isEqualTo("PROFESSIONAL_EMAIL_LINKED");
    }

    @Test
    void deletesFirebaseAndLocalUserOnlyForExclusivePrekinderAccount() {
        UserEntity user = activeUser(73L, Role.PREKINDER_PROFESSIONAL);
        user.setFirebaseUid("firebase-73");
        when(users.findById(73L)).thenReturn(Optional.of(user));

        assertThat(service.deleteExclusiveAccount(73L)).isTrue();

        verify(tokens).revokeAllForUser(user, "PREKINDER_PROFESSIONAL_DELETED");
        verify(sessions).deleteByUser(user);
        verify(users).delete(user);
        verify(users).flush();
        verify(firebase).deleteUser("firebase-73");
    }

    @Test
    void deletingPrekinderProfileDoesNotDeleteSharedOtherCourseAccount() {
        UserEntity teacher = activeUser(41L, Role.TEACHER);
        teacher.setFirebaseUid("shared-firebase");
        when(users.findById(41L)).thenReturn(Optional.of(teacher));

        assertThat(service.deleteExclusiveAccount(41L)).isFalse();

        verify(users, never()).delete(any(UserEntity.class));
        verify(firebase, never()).deleteUser(any());
    }

    private static UserEntity activeUser(long id, Role role) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setEmail("user@mtn.cl");
        user.setRole(role);
        user.setActive(true);
        return user;
    }
}
