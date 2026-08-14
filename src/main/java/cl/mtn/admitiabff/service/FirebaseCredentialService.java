package cl.mtn.admitiabff.service;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.UserRecord;
import org.springframework.stereotype.Service;

/** Aísla las mutaciones de Firebase Admin para poder probar el flujo sin el proveedor. */
@Service
public class FirebaseCredentialService {
    public record ResolvedUser(String uid, String email) {}

    public ResolvedUser createUser(String email, String password, String displayName) {
        requireConfigured();
        try {
            UserRecord user = FirebaseAuth.getInstance().createUser(
                new UserRecord.CreateRequest()
                    .setEmail(email)
                    .setPassword(password)
                    .setDisplayName(displayName)
                    .setEmailVerified(false)
                    .setDisabled(false));
            return new ResolvedUser(user.getUid(), user.getEmail());
        } catch (Exception ex) {
            throw new IllegalStateException("No fue posible crear la cuenta del profesional en Firebase", ex);
        }
    }

    public ResolvedUser resolveByEmail(String email) {
        requireConfigured();
        try {
            UserRecord user = FirebaseAuth.getInstance().getUserByEmail(email);
            if (user.getEmail() == null || !user.getEmail().equalsIgnoreCase(email)) {
                throw new IllegalStateException("La identidad Firebase no coincide con el correo del usuario");
            }
            return new ResolvedUser(user.getUid(), user.getEmail());
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("No existe una identidad Firebase válida para el correo del usuario", ex);
        }
    }

    public void updatePassword(String firebaseUid, String password) {
        requireConfigured();
        try {
            FirebaseAuth.getInstance().updateUser(
                new UserRecord.UpdateRequest(firebaseUid).setPassword(password));
        } catch (Exception ex) {
            throw new IllegalStateException("No fue posible actualizar la credencial en Firebase", ex);
        }
    }

    public void revokeRefreshTokens(String firebaseUid) {
        requireConfigured();
        try {
            FirebaseAuth.getInstance().revokeRefreshTokens(firebaseUid);
        } catch (Exception ex) {
            throw new IllegalStateException("No fue posible revocar las sesiones de Firebase", ex);
        }
    }

    public void deleteUser(String firebaseUid) {
        requireConfigured();
        try {
            FirebaseAuth.getInstance().deleteUser(firebaseUid);
        } catch (Exception ex) {
            throw new IllegalStateException("No fue posible revertir la cuenta creada en Firebase", ex);
        }
    }

    private void requireConfigured() {
        if (FirebaseApp.getApps().isEmpty()) {
            throw new IllegalStateException("Firebase Admin no está configurado");
        }
    }
}
