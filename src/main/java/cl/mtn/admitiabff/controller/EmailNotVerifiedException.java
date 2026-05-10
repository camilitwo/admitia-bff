package cl.mtn.admitiabff.controller;

/**
 * Indica que el usuario intentó iniciar sesión sin haber verificado su correo
 * electrónico. Se mapea a {@code 403 FORBIDDEN} con
 * {@code code = "EMAIL_NOT_VERIFIED"} y el email incluido para que el front
 * pueda ofrecer el botón de "reenviar correo de verificación".
 */
public class EmailNotVerifiedException extends RuntimeException {

    private final String email;

    public EmailNotVerifiedException(String email, String message) {
        super(message);
        this.email = email;
    }

    public String getEmail() {
        return email;
    }
}

