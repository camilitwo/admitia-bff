package cl.mtn.admitiabff.prekinder.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PrekinderAgePolicyTest {
    private static final Instant SANTIAGO_MIDNIGHT = Instant.parse("2026-08-06T04:00:00Z");

    @Test
    void acceptsExactThirdAndFourthBirthday() {
        assertThatCode(() -> PrekinderAgePolicy.validate(LocalDate.of(2023, 8, 6), SANTIAGO_MIDNIGHT))
            .doesNotThrowAnyException();
        assertThatCode(() -> PrekinderAgePolicy.validate(LocalDate.of(2022, 8, 6), SANTIAGO_MIDNIGHT))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsOneDayBeforeThirdBirthdayAndFifthBirthday() {
        assertThatThrownBy(() -> PrekinderAgePolicy.validate(LocalDate.of(2023, 8, 7), SANTIAGO_MIDNIGHT))
            .isInstanceOf(PrekinderDomainException.class)
            .extracting("code").isEqualTo("AGE_NOT_ELIGIBLE");
        assertThatThrownBy(() -> PrekinderAgePolicy.validate(LocalDate.of(2021, 8, 6), SANTIAGO_MIDNIGHT))
            .isInstanceOf(PrekinderDomainException.class);
    }

    @Test
    void usesSantiagoDateInsteadOfUtcDate() {
        Instant stillAugustFifthInSantiago = Instant.parse("2026-08-06T03:30:00Z");
        assertThatThrownBy(() -> PrekinderAgePolicy.validate(LocalDate.of(2023, 8, 6), stillAugustFifthInSantiago))
            .isInstanceOf(PrekinderDomainException.class);
    }
}
