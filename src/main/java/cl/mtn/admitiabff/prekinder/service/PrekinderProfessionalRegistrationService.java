package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.UserRepository;
import cl.mtn.admitiabff.util.JsonSupport;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderProfessionalRegistrationService {
    private final NamedParameterJdbcTemplate prekinderJdbc;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JsonSupport jsonSupport;

    public PrekinderProfessionalRegistrationService(
            @Qualifier("prekinderJdbc") NamedParameterJdbcTemplate prekinderJdbc,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JsonSupport jsonSupport) {
        this.prekinderJdbc = prekinderJdbc;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jsonSupport = jsonSupport;
    }

    @Transactional
    public RegistrationResult register(String rawEmail, String password) {
        String email = normalizeEmail(rawEmail);
        ProfessionalCandidate professional = findActiveProfessional(email);
        if (professional == null) {
            throw PrekinderDomainException.badRequest("PROFESSIONAL_NOT_FOUND",
                "El correo no corresponde a un profesional activo de Prekínder. Contacta a coordinación.");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw PrekinderDomainException.conflict("PROFESSIONAL_ALREADY_REGISTERED",
                "Este correo ya completó su registro. Ingresa con tu contraseña o solicita ayuda a coordinación.");
        }

        NameParts name = splitName(professional.displayName());
        UserEntity user = new UserEntity();
        user.setFirstName(name.firstName());
        user.setLastName(name.lastName());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        // Este rol sólo habilita la puerta del portal de profesores. Dentro del módulo
        // aislado, Prekínder conserva y aplica el rol PK_* del perfil profesional.
        user.setRole(Role.PREKINDER_PROFESSIONAL);
        user.setActive(true);
        user.setEmailVerified(false);
        user.setPreferencesJson(jsonSupport.write(Map.of("prekinderProfessional", true)));

        try {
            UserEntity saved = userRepository.saveAndFlush(user);
            return new RegistrationResult(saved.getId(), saved.getEmail(), professional.displayName());
        } catch (DataIntegrityViolationException exception) {
            throw PrekinderDomainException.conflict("PROFESSIONAL_ALREADY_REGISTERED",
                "Este correo ya completó su registro. Ingresa con tu contraseña o solicita ayuda a coordinación.");
        }
    }

    private ProfessionalCandidate findActiveProfessional(String email) {
        List<ProfessionalCandidate> matches = prekinderJdbc.query("""
            SELECT p.professional_id, p.display_name
              FROM professional_profiles p
             WHERE lower(p.email) = :email
               AND p.active = true
             ORDER BY p.created_at
             LIMIT 1
            """, Map.of("email", email), (rs, row) -> new ProfessionalCandidate(
                rs.getObject("professional_id", java.util.UUID.class), rs.getString("display_name")));
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    static NameParts splitName(String displayName) {
        String normalized = displayName == null ? "" : displayName.trim().replaceAll("\\s+", " ");
        int separator = normalized.indexOf(' ');
        if (separator < 0) return new NameParts(normalized, "Prekínder");
        return new NameParts(normalized.substring(0, separator), normalized.substring(separator + 1));
    }

    record ProfessionalCandidate(java.util.UUID professionalId, String displayName) {}
    record NameParts(String firstName, String lastName) {}
    public record RegistrationResult(Long userId, String email, String displayName) {}
}
