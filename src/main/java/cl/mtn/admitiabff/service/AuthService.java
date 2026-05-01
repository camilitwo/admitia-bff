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
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuthService {
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
        UserEntity user = userRepository.findByEmailIgnoreCase(email).orElseThrow(() -> new IllegalArgumentException("Credenciales inválidas"));
        if (!user.isActive() || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("Credenciales inválidas");
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
            "user", toAuthUser(user)
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
        String token = jwtService.generateToken(savedUser.getId(), savedUser.getEmail(), savedUser.getRole().name());
        return Map.of("success", true, "token", token, "user", toAuthUser(savedUser));
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

        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
            .or(() -> userRepository.findByEmailIgnoreCase(email))
            .orElseThrow(() -> new IllegalArgumentException("Usuario no registrado"));

        if (user.getFirebaseUid() == null) {
            user.setFirebaseUid(firebaseUid);
        }
        user.setLastLoginAt(LocalDateTime.now());
        if (decoded.isEmailVerified()) {
            user.setEmailVerified(true);
        }
        userRepository.save(user);

        String token = jwtService.generateToken(user.getId(), user.getEmail(), user.getRole().name());
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
            "user", toAuthUser(user)
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
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("El email ya existe");
        }

        UserEntity user = new UserEntity();
        user.setFirebaseUid(decoded.getUid());
        user.setEmail(email);
        user.setFirstName(stringValue(payload.get("firstName")));
        user.setLastName(stringValue(payload.get("lastName")));
        user.setRut(stringValue(payload.get("rut")));
        user.setPhone(stringValue(payload.get("phone")));
        user.setRole(Role.APODERADO);
        user.setActive(true);
        user.setEmailVerified(decoded.isEmailVerified());
        user.setPasswordHash("FIREBASE_MANAGED");
        user.setPreferencesJson(jsonSupport.write(Map.of()));
        UserEntity saved = userRepository.save(user);

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
            "user", toAuthUser(saved)
        );
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
        return userRepository.findById(auth.id()).orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));
    }

    public Map<String, Object> toAuthUser(UserEntity user) {
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("id", user.getId());
        response.put("firstName", user.getFirstName());
        response.put("lastName", user.getLastName());
        response.put("email", user.getEmail());
        response.put("rut", user.getRut());
        response.put("phone", user.getPhone());
        response.put("role", user.getRole().name());
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
