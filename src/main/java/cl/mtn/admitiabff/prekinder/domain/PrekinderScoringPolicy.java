package cl.mtn.admitiabff.prekinder.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fórmula canónica. No decide admisión: sólo produce un antecedente para el comité. */
public final class PrekinderScoringPolicy {
    private PrekinderScoringPolicy() {}

    public static Score calculate(BigDecimal academicRaw, BigDecimal academicMaximum,
                                  BigDecimal psychologyRaw, BigDecimal psychologyMaximum,
                                  BigDecimal psychomotorRaw, BigDecimal psychomotorMaximum) {
        BigDecimal academic = normalize(academicRaw, academicMaximum, "Académica");
        BigDecimal psychology = normalize(psychologyRaw, psychologyMaximum, "Psicología");
        BigDecimal psychomotor = normalize(psychomotorRaw, psychomotorMaximum, "Psicomotricidad");
        BigDecimal total = academic.multiply(PrekinderPolicyCodes.ACADEMIC_WEIGHT)
            .add(psychology.multiply(PrekinderPolicyCodes.PSYCHOLOGY_WEIGHT))
            .add(psychomotor.multiply(PrekinderPolicyCodes.PSYCHOMOTOR_WEIGHT))
            .setScale(6, RoundingMode.HALF_UP);
        Map<String, BigDecimal> components = new LinkedHashMap<>();
        components.put(PrekinderPolicyCodes.ACADEMIC, academic);
        components.put(PrekinderPolicyCodes.PSYCHOLOGY, psychology);
        components.put(PrekinderPolicyCodes.PSYCHOMOTOR, psychomotor);
        return new Score(total, components);
    }

    private static BigDecimal normalize(BigDecimal raw, BigDecimal maximum, String label) {
        if (raw == null) throw new IllegalArgumentException("Falta el puntaje de " + label);
        if (maximum == null || maximum.signum() <= 0)
            throw new IllegalArgumentException("Falta el máximo configurado de " + label);
        if (raw.signum() < 0 || raw.compareTo(maximum) > 0)
            throw new IllegalArgumentException("El puntaje de " + label + " está fuera de rango");
        return raw.divide(maximum, 10, RoundingMode.HALF_UP);
    }

    public record Score(BigDecimal total, Map<String, BigDecimal> normalizedComponents) {}
}
