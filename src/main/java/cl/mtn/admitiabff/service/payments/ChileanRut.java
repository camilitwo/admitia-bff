package cl.mtn.admitiabff.service.payments;

final class ChileanRut {
    private ChileanRut() {}

    record Parts(String body, String verifier) {}

    static Parts parse(String raw, String label) {
        String normalized = raw == null ? "" : raw.replace(".", "").replace("-", "").trim().toUpperCase();
        if (!normalized.matches("[0-9]{7,8}[0-9K]")) {
            throw PaymentIntegrationException.invalidData("El RUT de " + label + " es obligatorio o tiene un formato inválido");
        }
        String body = normalized.substring(0, normalized.length() - 1);
        String verifier = normalized.substring(normalized.length() - 1);
        if (!expectedVerifier(body).equals(verifier)) {
            throw PaymentIntegrationException.invalidData("El dígito verificador del RUT de " + label + " es inválido");
        }
        return new Parts(body, verifier);
    }

    private static String expectedVerifier(String body) {
        int sum = 0;
        int multiplier = 2;
        for (int index = body.length() - 1; index >= 0; index--) {
            sum += Character.digit(body.charAt(index), 10) * multiplier;
            multiplier = multiplier == 7 ? 2 : multiplier + 1;
        }
        int value = 11 - (sum % 11);
        if (value == 11) return "0";
        if (value == 10) return "K";
        return String.valueOf(value);
    }
}
