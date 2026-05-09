package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.config.AuthContext;
import cl.mtn.admitiabff.config.JwtService;
import cl.mtn.admitiabff.config.RsaKeyService;
import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.domain.user.ActiveSessionEntity;
import cl.mtn.admitiabff.domain.user.EmailVerificationCodeEntity;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.ActiveSessionRepository;
import cl.mtn.admitiabff.repository.EmailVerificationCodeRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import cl.mtn.admitiabff.util.JsonSupport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthService.class);

    private static final Set<Role> ADMIN_PORTAL_ROLES = Set.of(Role.ADMIN);

    private static final Set<Role> STAFF_PORTAL_ROLES = Set.of(
        Role.TEACHER, Role.COORDINATOR, Role.CYCLE_DIRECTOR,
        Role.PSYCHOLOGIST, Role.INTERVIEWER
    );

    private static final Set<Role> GUARDIAN_PORTAL_ROLES = Set.of(Role.APODERADO);
    private final UserRepository userRepository;
    private final ActiveSessionRepository activeSessionRepository;
    private final EmailVerificationCodeRepository verificationCodeRepository;
    private final JwtService jwtService;
    private final RsaKeyService rsaKeyService;
    private final PasswordEncoder passwordEncoder;
    private final JsonSupport jsonSupport;

    public AuthService(UserRepository userRepository, ActiveSessionRepository activeSessionRepository, EmailVerificationCodeRepository verificationCodeRepository, JwtService jwtService, RsaKeyService rsaKeyService, PasswordEncoder passwordEncoder, JsonSupport jsonSupport) {
        this.userRepository = userRepository;
        this.activeSessionRepository = activeSessionRepository;
        this.verificationCodeRepository = verificationCodeRepository;
        this.jwtService = jwtService;
        this.rsaKeyService = rsaKeyService;
        this.passwordEncoder = passwordEncoder;
        this.jsonSupport = jsonSupport;
    }

    public Map<String, Object> csrfToken() {
        String token = UUID.randomUUID().toString();
        return Map.of("success", true, "csrfToken", token);
    }

    public Map<String, Object> publicKey() {
        return Map.of("success", true, "data", rsaKeyService.publicKeyInfo());
    }

    @Transactional
    public Map<String, Object> login(Map<String, Object> payload, String userAgent, String ipAddress) {
        payload = normalizePayload(payload);
        String email = decrypt(payload, "email").trim().toLowerCase();
        String password = decrypt(payload, "password");
        String portalType = stringValue(payload.get("portalType")).trim().toUpperCase();
        UserEntity user = userRepository.findByEmailIgnoreCase(email).orElseThrow(() -> new IllegalArgumentException("Credenciales inválidas"));
        if (!user.isActive() || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("Credenciales inválidas");
        }
        if (!portalType.isEmpty()) {
            Set<Role> allowedRoles = switch (portalType) {
                case "ADMIN" -> ADMIN_PORTAL_ROLES;
                case "STAFF" -> STAFF_PORTAL_ROLES;
                case "GUARDIAN" -> GUARDIAN_PORTAL_ROLES;
                default -> throw new IllegalArgumentException("Portal desconocido: " + portalType);
            };
            if (!allowedRoles.contains(user.getRole())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Su cuenta no tiene acceso a este portal. Por favor use el portal correspondiente a su rol.");
            }
        }
        // OPCIONAL — linking en línea con Firebase si el cliente envía un idToken válido.
        // Sólo enlazamos si: (a) el usuario aún no tiene firebase_uid, (b) el idToken pertenece
        // al MISMO email autenticado por password, y (c) el email está verificado en Firebase.
        // Esto cierra la brecha del registro de apoderados que nunca quedó asociado a su UID.
        String optionalIdToken = stringValue(payload.get("firebaseIdToken"));
        if (!optionalIdToken.isBlank()) {
            linkFirebaseInline(user, optionalIdToken);
        }
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
        activeSessionRepository.deleteByUser(user);
        String token = jwtService.generateToken(user.getId(), user.getEmail(), user.getRole().name());
        ActiveSessionEntity session = new ActiveSessionEntity();
        session.setUser(user);
        session.setTokenHash(sha256(token));
        session.setCreatedAt(LocalDateTime.now());
        session.setLastActivity(LocalDateTime.now());
        session.setUserAgent(userAgent);
        session.setIpAddress(ipAddress);
        activeSessionRepository.save(session);
        return Map.of(
            "success", true,
            "token", token,
            "refreshToken", token,
            "expiresAt", LocalDateTime.now().plusHours(12).toString(),
            "refreshExpiresAt", LocalDateTime.now().plusHours(12).toString(),
            "sessionId", session.getId().toString(),
            "permissions", List.of(user.getRole().name()),
            "user", toAuthUser(user),
            "firebaseLinked", user.getFirebaseUid() != null
        );
    }

    @Transactional
    public Map<String, Object> register(Map<String, Object> payload) {
        payload = normalizePayload(payload);
        String email = decrypt(payload, "email").trim().toLowerCase();
        String password = decrypt(payload, "password");
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("El email ya existe");
        }
        UserEntity user = new UserEntity();
        user.setFirstName(stringValue(payload.get("firstName")));
        user.setLastName(stringValue(payload.get("lastName")));
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(Role.APODERADO);
        user.setRut(stringValue(payload.get("rut")));
        user.setPhone(stringValue(payload.get("phone")));
        user.setActive(true);
        user.setEmailVerified(false);
        user.setPreferencesJson(jsonSupport.write(Map.of()));
        UserEntity savedUser = userRepository.save(user);

        // Si el front nos pasó también el idToken de Firebase del mismo email, lo enlazamos en el
        // mismo registro. Esto cierra el bug histórico en que el apoderado quedaba con firebase_uid=null.
        String optionalIdToken = stringValue(payload.get("firebaseIdToken"));
        if (!optionalIdToken.isBlank()) {
            linkFirebaseInline(savedUser, optionalIdToken);
            savedUser = userRepository.save(savedUser);
        }

        String token = jwtService.generateToken(savedUser.getId(), savedUser.getEmail(), savedUser.getRole().name());
        return Map.of("success", true, "token", token, "user", toAuthUser(savedUser),
            "firebaseLinked", savedUser.getFirebaseUid() != null);
    }

    public Map<String, Object> checkEmail(Map<String, Object> payload) {
        payload = normalizePayload(payload);
        String email = stringValue(payload.get("email")).trim().toLowerCase();
        return Map.of("exists", userRepository.existsByEmailIgnoreCase(email), "email", email);
    }

    public Map<String, Object> check() {
        return Map.of("success", true, "user", toAuthUser(requireAuthenticatedUser()));
    }

    @Transactional
    public Map<String, Object> changePassword(Map<String, Object> payload) {
        payload = normalizePayload(payload);
        UserEntity user = requireAuthenticatedUser();
        String currentPassword = stringValue(payload.get("currentPassword"));
        String newPassword = stringValue(payload.get("newPassword"));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("La contraseña actual no coincide");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        return Map.of("success", true, "message", "Contraseña actualizada correctamente");
    }

    @Transactional
    public Map<String, Object> firebaseLogin(Map<String, Object> payload) {
        String idToken = stringValue(payload.get("idToken"));
        com.google.firebase.auth.FirebaseToken decoded;
        try {
            decoded = com.google.firebase.auth.FirebaseAuth.getInstance().verifyIdToken(idToken);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Token de Firebase inválido");
        }
        String email = decoded.getEmail();
        String firebaseUid = decoded.getUid();
        if (email == null || email.isBlank() || firebaseUid == null || firebaseUid.isBlank()) {
            throw new IllegalArgumentException("Token de Firebase sin email o uid");
        }

        // Buscar primero por UID (fuente de verdad federada). Si no, buscar por email para
        // permitir el primer linking — pero CON validaciones, no automático.
        UserEntity byUid = userRepository.findByFirebaseUid(firebaseUid).orElse(null);
        UserEntity byEmail = userRepository.findByEmailIgnoreCase(email).orElse(null);

        UserEntity user;
        if (byUid != null) {
            // Caso normal: el UID ya está enlazado. Validar que el email no haya cambiado.
            if (byUid.getEmail() != null && !email.equalsIgnoreCase(byUid.getEmail())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El email del token no coincide con el email registrado para esta cuenta. Contacte al administrador.");
            }
            user = byUid;
        } else if (byEmail != null) {
            // Primer ingreso con Firebase de una cuenta local pre-existente → exigir verificación
            // y que el slot de firebase_uid esté libre. Esto bloquea account takeover.
            if (byEmail.getFirebaseUid() != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Esta cuenta ya está enlazada a otra identidad de Firebase. Contacte al administrador.");
            }
            if (!decoded.isEmailVerified()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Verifique su email en el proveedor antes de enlazar la cuenta.");
            }
            byEmail.setFirebaseUid(firebaseUid);
            log.info("[Auth/Firebase] Linking user id={} email={} → firebase_uid={}", byEmail.getId(), email, firebaseUid);
            user = byEmail;
        } else {
            throw new IllegalArgumentException("Usuario no registrado");
        }

        if (!user.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cuenta inactiva");
        }
        user.setLastLoginAt(LocalDateTime.now());
        if (decoded.isEmailVerified()) {
            user.setEmailVerified(true);
        }
        userRepository.save(user);

        String token = jwtService.generateToken(user.getId(), user.getEmail(), user.getRole().name());
        activeSessionRepository.deleteByUser(user);
        ActiveSessionEntity session = new ActiveSessionEntity();
        session.setUser(user);
        session.setTokenHash(sha256(token));
        session.setCreatedAt(LocalDateTime.now());
        session.setLastActivity(LocalDateTime.now());
        activeSessionRepository.save(session);

        return Map.of(
            "success", true,
            "token", token,
            "refreshToken", token,
            "expiresAt", LocalDateTime.now().plusHours(12).toString(),
            "refreshExpiresAt", LocalDateTime.now().plusHours(12).toString(),
            "permissions", List.of(user.getRole().name()),
            "user", toAuthUser(user),
            "firebaseLinked", true
        );
    }

    @Transactional
    public Map<String, Object> firebaseRegister(Map<String, Object> payload) {
        String idToken = stringValue(payload.get("idToken"));
        com.google.firebase.auth.FirebaseToken decoded;
        try {
            decoded = com.google.firebase.auth.FirebaseAuth.getInstance().verifyIdToken(idToken);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Token de Firebase inválido");
        }
        String email = decoded.getEmail();
        String firebaseUid = decoded.getUid();
        if (email == null || email.isBlank() || firebaseUid == null || firebaseUid.isBlank()) {
            throw new IllegalArgumentException("Token de Firebase sin email o uid");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("El email ya existe");
        }
        if (userRepository.findByFirebaseUid(firebaseUid).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta identidad de Firebase ya está enlazada a otro usuario");
        }
        if (!decoded.isEmailVerified()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Debe verificar su email antes de completar el registro");
        }

        UserEntity user = new UserEntity();
        user.setFirebaseUid(firebaseUid);
        user.setEmail(email);
        user.setFirstName(stringValue(payload.get("firstName")));
        user.setLastName(stringValue(payload.get("lastName")));
        user.setRut(stringValue(payload.get("rut")));
        user.setPhone(stringValue(payload.get("phone")));
        user.setRole(Role.APODERADO);
        user.setActive(true);
        user.setEmailVerified(true);
        user.setPasswordHash("FIREBASE_MANAGED");
        user.setPreferencesJson(jsonSupport.write(Map.of()));
        UserEntity saved = userRepository.save(user);
        log.info("[Auth/Firebase] Registered new user id={} email={} firebase_uid={}", saved.getId(), email, firebaseUid);

        String token = jwtService.generateToken(saved.getId(), saved.getEmail(), saved.getRole().name());
        ActiveSessionEntity session = new ActiveSessionEntity();
        session.setUser(saved);
        session.setTokenHash(sha256(token));
        session.setCreatedAt(LocalDateTime.now());
        session.setLastActivity(LocalDateTime.now());
        activeSessionRepository.save(session);

        return Map.of(
            "success", true,
            "token", token,
            "refreshToken", token,
            "expiresAt", LocalDateTime.now().plusHours(12).toString(),
            "refreshExpiresAt", LocalDateTime.now().plusHours(12).toString(),
            "permissions", List.of(saved.getRole().name()),
            "user", toAuthUser(saved),
            "firebaseLinked", true
        );
    }

    /**
     * Endpoint explícito para enlazar la cuenta del usuario AUTENTICADO con su identidad de Firebase.
     * Pensado para apoderados ya creados que nunca quedaron asociados a su firebase_uid (bug histórico).
     * Reglas:
     *  - El usuario debe estar autenticado (cookie/JWT del BFF).
     *  - El email del idToken debe coincidir con el del usuario.
     *  - El email del idToken debe estar verificado en Firebase.
     *  - El usuario no debe tener ya otro firebase_uid.
     *  - Ese firebase_uid no debe estar usado por otro usuario.
     */
    @Transactional
    public Map<String, Object> linkFirebase(Map<String, Object> payload) {
        UserEntity user = requireAuthenticatedUser();
        String idToken = stringValue(payload.get("idToken"));
        if (idToken.isBlank()) {
            throw new IllegalArgumentException("idToken requerido");
        }
        com.google.firebase.auth.FirebaseToken decoded;
        try {
            decoded = com.google.firebase.auth.FirebaseAuth.getInstance().verifyIdToken(idToken);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Token de Firebase inválido");
        }
        String firebaseUid = decoded.getUid();
        String email = decoded.getEmail();

        if (email == null || !email.equalsIgnoreCase(user.getEmail())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "El email del token de Firebase no coincide con el email de la cuenta");
        }
        if (!decoded.isEmailVerified()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "El email no está verificado en Firebase");
        }
        if (user.getFirebaseUid() != null) {
            if (user.getFirebaseUid().equals(firebaseUid)) {
                return Map.of("success", true, "firebaseLinked", true, "message", "Ya estaba enlazado");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "La cuenta ya está enlazada a otra identidad de Firebase");
        }
        userRepository.findByFirebaseUid(firebaseUid).ifPresent(other -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Ese firebase_uid ya está usado por otro usuario (id=" + other.getId() + ")");
        });

        user.setFirebaseUid(firebaseUid);
        user.setEmailVerified(true);
        userRepository.save(user);
        log.info("[Auth/Firebase] Linked (explicit) user id={} email={} → firebase_uid={}", user.getId(), email, firebaseUid);
        return Map.of("success", true, "firebaseLinked", true);
    }

    /**
     * Linking en línea durante /api/auth/login. Best-effort: si algo falla, NO rompemos el login
     * (sólo log de advertencia). Las verificaciones de seguridad son las mismas que en linkFirebase.
     */
    private void linkFirebaseInline(UserEntity user, String idToken) {
        try {
            com.google.firebase.auth.FirebaseToken decoded =
                com.google.firebase.auth.FirebaseAuth.getInstance().verifyIdToken(idToken);
            String firebaseUid = decoded.getUid();
            String email = decoded.getEmail();
            if (email == null || !email.equalsIgnoreCase(user.getEmail())) {
                log.warn("[Auth/Firebase] Linking inline rechazado: email del token ({}) != email del usuario ({})",
                    email, user.getEmail());
                return;
            }
            if (!decoded.isEmailVerified()) {
                log.warn("[Auth/Firebase] Linking inline rechazado: email no verificado para user id={}", user.getId());
                return;
            }
            if (user.getFirebaseUid() != null) {
                if (!user.getFirebaseUid().equals(firebaseUid)) {
                    log.warn("[Auth/Firebase] Linking inline rechazado: user id={} ya tiene otro firebase_uid", user.getId());
                }
                return;
            }
            if (userRepository.findByFirebaseUid(firebaseUid).isPresent()) {
                log.warn("[Auth/Firebase] Linking inline rechazado: firebase_uid={} ya usado por otro usuario", firebaseUid);
                return;
            }
            user.setFirebaseUid(firebaseUid);
            user.setEmailVerified(true);
            log.info("[Auth/Firebase] Inline linked user id={} email={} → firebase_uid={}", user.getId(), email, firebaseUid);
        } catch (Exception ex) {
            log.warn("[Auth/Firebase] Linking inline falló: {}", ex.getMessage());
        }
    }

    public AuthContextHolder requireAuth() {
        UserEntity user = requireAuthenticatedUser();
        return new AuthContextHolder(user.getId(), user.getEmail(), user.getRole().name());
    }

    public UserEntity requireAuthenticatedUser() {
        var auth = AuthContext.get();
        if (auth == null) {
            throw new IllegalArgumentException("No autenticado");
        }
        if (auth.id() != null) {
            return userRepository.findById(auth.id()).orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));
        }
        if (auth.email() != null && !auth.email().isBlank()) {
            return userRepository.findByEmailIgnoreCase(auth.email()).orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));
        }
        throw new IllegalArgumentException("No autenticado");
    }

    public Map<String, Object> toAuthUser(UserEntity user) {
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("id", user.getId());
        response.put("firstName", user.getFirstName());
        response.put("lastName", user.getLastName());
        response.put("email", user.getEmail());
        response.put("rut", user.getRut());
        response.put("phone", user.getPhone());
        response.put("role", user.getRole().name().toUpperCase());
        response.put("subject", user.getSubject());
        response.put("educationalLevel", user.getEducationalLevel());
        response.put("active", user.isActive());
        response.put("emailVerified", user.isEmailVerified());
        response.put("lastLoginAt", user.getLastLoginAt());
        response.put("preferences", jsonSupport.readMap(user.getPreferencesJson()));
        return response;
    }

    public String hashPassword(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    public EmailVerificationCodeEntity findValidVerificationCode(String email, String code) {
        return verificationCodeRepository.findFirstByEmailIgnoreCaseAndCodeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(email, code, LocalDateTime.now())
            .orElseThrow(() -> new IllegalArgumentException("Código inválido o expirado"));
    }

    private String decrypt(Map<String, Object> payload, String key) {
        return rsaKeyService.decryptIfNeeded(stringValue(payload.get(key)));
    }

    private Map<String, Object> normalizePayload(Map<String, Object> payload) {
        if (payload.containsKey("encryptedData") && payload.containsKey("encryptedKey") && payload.containsKey("iv") && payload.containsKey("authTag")) {
            Map<String, Object> decrypted = jsonSupport.readMap(rsaKeyService.decryptHybridPayload(payload));
            if (decrypted.isEmpty()) {
                throw new IllegalArgumentException("Payload de autenticación inválido");
            }
            return decrypted;
        }
        return payload;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public record AuthContextHolder(Long id, String email, String role) {
    }
}
