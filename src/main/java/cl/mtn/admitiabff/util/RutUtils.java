package cl.mtn.admitiabff.util;

public final class RutUtils {
    private RutUtils() {
    }

    public static boolean isValid(String rut) {
        if (rut == null) {
            return false;
        }
        String clean = rut.replace(".", "").replace("-", "").toUpperCase();
        if (clean.length() < 2) {
            return false;
        }
        String body = clean.substring(0, clean.length() - 1);
        char dv = clean.charAt(clean.length() - 1);
        int sum = 0;
        int multiplier = 2;
        for (int i = body.length() - 1; i >= 0; i--) {
            sum += Character.getNumericValue(body.charAt(i)) * multiplier;
            multiplier = multiplier == 7 ? 2 : multiplier + 1;
        }
        int mod = 11 - (sum % 11);
        char expected = mod == 11 ? '0' : mod == 10 ? 'K' : Character.forDigit(mod, 10);
        return expected == dv;
    }
}
