package cl.mtn.admitiabff.prekinder.service;

final class PrekinderRut {
    private PrekinderRut() {}

    static String normalize(String raw) {
        String value = raw == null ? "" : raw.replace(".", "").replace("-", "").trim().toUpperCase();
        if (!value.matches("[0-9]{7,8}[0-9K]")) {
            throw new IllegalArgumentException("El RUT del postulante no es válido");
        }
        String body = value.substring(0, value.length() - 1);
        int sum = 0;
        int multiplier = 2;
        for (int index = body.length() - 1; index >= 0; index--) {
            sum += Character.digit(body.charAt(index), 10) * multiplier;
            multiplier = multiplier == 7 ? 2 : multiplier + 1;
        }
        int result = 11 - (sum % 11);
        String expected = result == 11 ? "0" : result == 10 ? "K" : String.valueOf(result);
        if (!expected.equals(value.substring(value.length() - 1))) {
            throw new IllegalArgumentException("El dígito verificador del RUT no es válido");
        }
        return body + "-" + expected;
    }
}
