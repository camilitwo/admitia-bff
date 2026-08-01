package cl.mtn.admitiabff.service;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.UserRecord;
import org.springframework.stereotype.Service;

/** Aísla las mutaciones de Firebase Admin para poder probar el flujo sin el proveedor. */
@Service
public class FirebaseCredentialService {
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

    private void requireConfigured() {
        if (FirebaseApp.getApps().isEmpty()) {
            throw new IllegalStateException("Firebase Admin no está configurado");
        }
    }
}
