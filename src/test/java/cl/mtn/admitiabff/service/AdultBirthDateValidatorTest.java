package cl.mtn.admitiabff.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AdultBirthDateValidatorTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 23);

    @Test
    void acceptsTheExactEighteenthBirthday() {
        assertDoesNotThrow(() -> AdultBirthDateValidator.requireAdult(TODAY.minusYears(18), "padre", TODAY));
    }

    @Test
    void rejectsMissingFutureAndUnderageDates() {
        assertThrows(IllegalArgumentException.class, () -> AdultBirthDateValidator.requireAdult(null, "madre", TODAY));
        assertThrows(IllegalArgumentException.class, () -> AdultBirthDateValidator.requireAdult(TODAY.plusDays(1), "madre", TODAY));
        assertThrows(IllegalArgumentException.class, () -> AdultBirthDateValidator.requireAdult(TODAY.minusYears(18).plusDays(1), "madre", TODAY));
    }
}
