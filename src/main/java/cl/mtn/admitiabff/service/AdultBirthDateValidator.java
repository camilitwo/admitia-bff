package cl.mtn.admitiabff.service;

import java.time.LocalDate;

public final class AdultBirthDateValidator {
    private AdultBirthDateValidator() {}

    public static void requireAdult(LocalDate birthDate, String person, LocalDate today) {
        if (birthDate == null) {
            throw new IllegalArgumentException("La fecha de nacimiento de " + person + " es obligatoria");
        }
        if (birthDate.isAfter(today)) {
            throw new IllegalArgumentException("La fecha de nacimiento de " + person + " no puede ser futura");
        }
        if (birthDate.isAfter(today.minusYears(18))) {
            throw new IllegalArgumentException(person + " debe tener al menos 18 años");
        }
    }

    public static void requireAdult(LocalDate birthDate, String person) {
        requireAdult(birthDate, person, LocalDate.now());
    }
}
