package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.ActiveSessionRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import cl.mtn.admitiabff.service.FirebaseCredentialService;
import cl.mtn.admitiabff.service.TokenService;
import cl.mtn.admitiabff.util.JsonSupport;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderProfessionalAccountService {
    private final UserRepository users;
    private final FirebaseCredentialService firebase;
    private final JsonSupport jsonSupport;
    private final TokenService tokens;
    private final ActiveSessionRepository sessions;

    public PrekinderProfessionalAccountService(UserRepository users, FirebaseCredentialService firebase,
                                                JsonSupport jsonSupport, TokenService tokens,
                                                ActiveSessionRepository sessions) {
        this.users = users;
        this.firebase = firebase;
        this.jsonSupport = jsonSupport;
        this.tokens = tokens;
        this.sessions = sessions;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProvisionedAccount provision(String displayName, String rawEmail, String password) {
        String email = rawEmail.trim().toLowerCase();
        UserEntity existing = users.findByEmailIgnoreCase(email).orElse(null);
        if (existing != null && !existing.isActive()) {
            throw PrekinderDomainException.conflict("PROFESSIONAL_ACCOUNT_INACTIVE",
                "El correo pertenece a una cuenta inactiva. Reactívala antes de crear el perfil Prekínder.");
        }
        if (existing != null && existing.getFirebaseUid() != null && !existing.getFirebaseUid().isBlank()) {
            return new ProvisionedAccount(existing.getId(), existing.getFirebaseUid(), false, false);
        }

        FirebaseCredentialService.ResolvedUser firebaseUser = firebase.createUser(email, password, displayName);
        try {
            if (existing != null) {
                existing.setFirebaseUid(firebaseUser.uid());
                users.saveAndFlush(existing);
                return new ProvisionedAccount(existing.getId(), firebaseUser.uid(), false, true);
            }

            NameParts name = splitName(displayName);
            UserEntity user = new UserEntity();
            user.setFirstName(name.firstName());
            user.setLastName(name.lastName());
            user.setEmail(email);
            user.setPasswordHash("FIREBASE_MANAGED");
            user.setFirebaseUid(firebaseUser.uid());
            user.setRole(Role.PREKINDER_PROFESSIONAL);
            user.setActive(true);
            user.setEmailVerified(false);
            user.setPreferencesJson(jsonSupport.write(Map.of("prekinderProfessional", true)));
            UserEntity saved = users.saveAndFlush(user);
            return new ProvisionedAccount(saved.getId(), firebaseUser.uid(), true, true);
        } catch (RuntimeException exception) {
            firebase.deleteUser(firebaseUser.uid());
            throw exception;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void rollback(ProvisionedAccount account) {
        if (account == null || !account.firebaseCreated()) return;
        if (account.localUserCreated()) {
            users.deleteById(account.userId());
            users.flush();
        } else {
            users.findById(account.userId()).ifPresent(user -> {
                if (account.firebaseUid().equals(user.getFirebaseUid())) {
                    user.setFirebaseUid(null);
                    users.saveAndFlush(user);
                }
            });
        }
        firebase.deleteUser(account.firebaseUid());
    }

    @Transactional
    public void updatePassword(Long userId, String password) {
        UserEntity user = users.findById(userId)
            .orElseThrow(() -> PrekinderDomainException.conflict("PROFESSIONAL_ACCOUNT_NOT_FOUND",
                "El profesional no tiene una cuenta de acceso enlazada."));
        if (!user.isActive()) {
            throw PrekinderDomainException.conflict("PROFESSIONAL_ACCOUNT_INACTIVE",
                "La cuenta del profesional está inactiva.");
        }
        if (user.getFirebaseUid() == null || user.getFirebaseUid().isBlank()) {
            throw PrekinderDomainException.conflict("PROFESSIONAL_FIREBASE_NOT_LINKED",
                "El profesional no tiene una identidad Firebase enlazada.");
        }

        firebase.updatePassword(user.getFirebaseUid(), password);
        firebase.revokeRefreshTokens(user.getFirebaseUid());
        tokens.revokeAllForUser(user, "PREKINDER_PASSWORD_RESET");
        sessions.deleteByUser(user);
        user.setMustChangePassword(false);
        user.setTemporaryPasswordHash(null);
        user.setTemporaryPasswordExpiresAt(null);
        users.save(user);
    }

    @Transactional(readOnly = true)
    public void requireMatchingEmail(Long userId, String rawEmail) {
        UserEntity user = users.findById(userId)
            .orElseThrow(() -> PrekinderDomainException.conflict("PROFESSIONAL_ACCOUNT_NOT_FOUND",
                "El profesional no tiene una cuenta de acceso enlazada."));
        if (!user.getEmail().equalsIgnoreCase(rawEmail.trim())) {
            throw PrekinderDomainException.conflict("PROFESSIONAL_EMAIL_LINKED",
                "El correo no puede modificarse porque está enlazado a la identidad Firebase.");
        }
    }

    @Transactional
    public boolean deleteExclusiveAccount(Long userId) {
        if (userId == null) return false;
        UserEntity user = users.findById(userId).orElse(null);
        if (user == null || user.getRole() != Role.PREKINDER_PROFESSIONAL) return false;

        String firebaseUid = user.getFirebaseUid();
        tokens.revokeAllForUser(user, "PREKINDER_PROFESSIONAL_DELETED");
        sessions.deleteByUser(user);
        users.delete(user);
        users.flush();
        if (firebaseUid != null && !firebaseUid.isBlank()) {
            firebase.deleteUser(firebaseUid);
        }
        return true;
    }

    static NameParts splitName(String displayName) {
        String normalized = displayName.trim().replaceAll("\\s+", " ");
        int separator = normalized.indexOf(' ');
        return separator < 0
            ? new NameParts(normalized, "Prekínder")
            : new NameParts(normalized.substring(0, separator), normalized.substring(separator + 1));
    }

    record NameParts(String firstName, String lastName) {}
    public record ProvisionedAccount(Long userId, String firebaseUid, boolean localUserCreated,
                                     boolean firebaseCreated) {}
}
