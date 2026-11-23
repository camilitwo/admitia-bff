package cl.mtn.admitiabff.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cl.mtn.admitiabff.domain.person.ParentEntity;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ParentBirthDateContractTest {
    @Test
    void parentPersistsAnOptionalCalendarBirthDateForLegacyCompatibility() {
        ParentEntity legacy = new ParentEntity();
        assertNull(legacy.getBirthDate());

        LocalDate birthDate = LocalDate.of(1985, 7, 9);
        legacy.setBirthDate(birthDate);
        assertEquals(birthDate, legacy.getBirthDate());
    }
}
