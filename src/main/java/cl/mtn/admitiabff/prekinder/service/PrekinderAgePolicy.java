package cl.mtn.admitiabff.prekinder.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.time.ZoneId;
import org.springframework.http.HttpStatus;

public final class PrekinderAgePolicy {
    private static final ZoneId SANTIAGO = ZoneId.of("America/Santiago");

    private PrekinderAgePolicy() {}

    public static void validate(LocalDate birthDate, Instant submittedAt) {
        if (birthDate == null) throw new IllegalArgumentException("La fecha de nacimiento es obligatoria");
        LocalDate today = submittedAt.atZone(SANTIAGO).toLocalDate();
        int years = Period.between(birthDate, today).getYears();
        if (birthDate.isAfter(today) || (years != 3 && years != 4)) {
            throw new PrekinderDomainException("AGE_NOT_ELIGIBLE",
                "El postulante debe tener 3 o 4 años cumplidos al enviar la postulación",
                HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    public static void validate(LocalDate birthDate, LocalDate referenceDate, int minimumMonths, int maximumMonths) {
        if (birthDate == null) throw new IllegalArgumentException("La fecha de nacimiento es obligatoria");
        LocalDate effectiveDate = referenceDate == null ? LocalDate.now(SANTIAGO) : referenceDate;
        validateConfiguredRange(birthDate, effectiveDate, minimumMonths, maximumMonths);
    }

    public static void validate(LocalDate birthDate, LocalDate referenceDate, int academicYear,
                                int minimumMonths, int maximumMonths) {
        if (birthDate == null) throw new IllegalArgumentException("La fecha de nacimiento es obligatoria");
        LocalDate effectiveDate = referenceDate == null
            ? LocalDate.of(academicYear, 3, 31)
            : referenceDate;
        validateConfiguredRange(birthDate, effectiveDate, minimumMonths, maximumMonths, false, false);
    }

    public static void validate(LocalDate birthDate, LocalDate referenceDate, int academicYear,
                                int minimumMonths, int maximumMonths,
                                boolean inclusionStudent, boolean noMaxAgeForInclusion) {
        if (birthDate == null) throw new IllegalArgumentException("La fecha de nacimiento es obligatoria");
        LocalDate effectiveDate = referenceDate == null
            ? LocalDate.of(academicYear, 3, 31)
            : referenceDate;
        boolean skipMaxAge = inclusionStudent && noMaxAgeForInclusion;
        validateConfiguredRange(birthDate, effectiveDate, minimumMonths, maximumMonths, skipMaxAge, false);
    }

    private static void validateConfiguredRange(LocalDate birthDate, LocalDate effectiveDate,
                                                int minimumMonths, int maximumMonths) {
        validateConfiguredRange(birthDate, effectiveDate, minimumMonths, maximumMonths, false, false);
    }

    private static void validateConfiguredRange(LocalDate birthDate, LocalDate effectiveDate,
                                                int minimumMonths, int maximumMonths,
                                                boolean skipMaxAge, boolean skipMinAge) {
        long months = ChronoUnit.MONTHS.between(birthDate, effectiveDate);
        if (birthDate.isAfter(effectiveDate)) {
            throw new PrekinderDomainException("AGE_NOT_ELIGIBLE",
                "La fecha de nacimiento no puede ser posterior a la fecha de referencia",
                HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (!skipMinAge && months < minimumMonths) {
            throw new PrekinderDomainException("AGE_NOT_ELIGIBLE",
                "El postulante no cumple la edad mínima requerida (" + minimumMonths + " meses)",
                HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (!skipMaxAge && months > maximumMonths) {
            throw new PrekinderDomainException("AGE_NOT_ELIGIBLE",
                "El postulante excede la edad máxima permitida (" + maximumMonths + " meses)",
                HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }
}
