package cl.mtn.admitiabff.prekinder.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
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
}
