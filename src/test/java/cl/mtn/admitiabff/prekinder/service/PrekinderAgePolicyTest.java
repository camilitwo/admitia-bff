package cl.mtn.admitiabff.prekinder.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PrekinderAgePolicyTest {
    private static final Instant SANTIAGO_MIDNIGHT = Instant.parse("2026-08-06T04:00:00Z");
    private static final LocalDate REFERENCE_DATE = LocalDate.of(2027, 3, 31);
    private static final int ACADEMIC_YEAR = 2027;
    private static final int MINIMUM_MONTHS = 48;
    private static final int MAXIMUM_MONTHS = 71;

    // birthDate within range: 48-71 months before 2027-03-31
    private static final LocalDate BIRTHDATE_OK = LocalDate.of(2022, 3, 31); // 60 months
    // birthDate below minimum: < 48 months before 2027-03-31
    private static final LocalDate BIRTHDATE_BELOW_MIN = LocalDate.of(2023, 4, 1); // ~47 months
    // birthDate above maximum: > 71 months before 2027-03-31
    private static final LocalDate BIRTHDATE_ABOVE_MAX = LocalDate.of(2021, 3, 1); // 72 months

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

    @Test
    void usesMarch31OfAcademicYearWhenConfiguredReferenceDateIsMissing() {
        assertThatCode(() -> PrekinderAgePolicy.validate(
            LocalDate.of(2023, 1, 2), null, 2027, 48, 71))
            .doesNotThrowAnyException();
    }

    @Test
    void appliesMinimumAgeAgainstAcademicYearFallbackDate() {
        assertThatThrownBy(() -> PrekinderAgePolicy.validate(
            LocalDate.of(2023, 4, 1), null, 2027, 48, 71))
            .isInstanceOf(PrekinderDomainException.class)
            .extracting("code").isEqualTo("AGE_NOT_ELIGIBLE");
    }

    // --- Tests for validate(birthDate, referenceDate, academicYear, minimumMonths, maximumMonths, inclusionStudent, noMaxAgeForInclusion) ---

    @Test
    void inclusionStudentAndNoMaxAge_allowsAgeAboveMaximum() {
        // Case 3: inclusionStudent=true, noMaxAgeForInclusion=true, age sobre máximo → OK
        assertThatCode(() -> PrekinderAgePolicy.validate(
            BIRTHDATE_ABOVE_MAX, REFERENCE_DATE, ACADEMIC_YEAR,
            MINIMUM_MONTHS, MAXIMUM_MONTHS, true, true))
            .doesNotThrowAnyException();
    }

    @Test
    void inclusionStudentAndNoMaxAge_stillRejectsAgeBelowMinimum() {
        // Case 2: inclusionStudent=true, noMaxAgeForInclusion=true, age bajo del mínimo → ERROR
        assertThatThrownBy(() -> PrekinderAgePolicy.validate(
            BIRTHDATE_BELOW_MIN, REFERENCE_DATE, ACADEMIC_YEAR,
            MINIMUM_MONTHS, MAXIMUM_MONTHS, true, true))
            .isInstanceOf(PrekinderDomainException.class)
            .extracting("code").isEqualTo("AGE_NOT_ELIGIBLE");
    }

    @Test
    void inclusionStudentAndNoMaxAge_acceptsAgeWithinRange() {
        // Case 1: inclusionStudent=true, noMaxAgeForInclusion=true, age dentro de rango → OK
        assertThatCode(() -> PrekinderAgePolicy.validate(
            BIRTHDATE_OK, REFERENCE_DATE, ACADEMIC_YEAR,
            MINIMUM_MONTHS, MAXIMUM_MONTHS, true, true))
            .doesNotThrowAnyException();
    }

    @Test
    void noMaxAgeFlagIgnoredWhenNotInclusionStudent() {
        // Case 4: inclusionStudent=false, noMaxAgeForInclusion=true, age sobre máximo → ERROR
        assertThatThrownBy(() -> PrekinderAgePolicy.validate(
            BIRTHDATE_ABOVE_MAX, REFERENCE_DATE, ACADEMIC_YEAR,
            MINIMUM_MONTHS, MAXIMUM_MONTHS, false, true))
            .isInstanceOf(PrekinderDomainException.class)
            .extracting("code").isEqualTo("AGE_NOT_ELIGIBLE");
    }

    @Test
    void noMaxAgeFlagNotSet_stillEnforcesMaximumAge() {
        // Case 5: inclusionStudent=true, noMaxAgeForInclusion=false, age sobre máximo → ERROR
        assertThatThrownBy(() -> PrekinderAgePolicy.validate(
            BIRTHDATE_ABOVE_MAX, REFERENCE_DATE, ACADEMIC_YEAR,
            MINIMUM_MONTHS, MAXIMUM_MONTHS, true, false))
            .isInstanceOf(PrekinderDomainException.class)
            .extracting("code").isEqualTo("AGE_NOT_ELIGIBLE");
    }

    @Test
    void nullBirthDate_throwsIllegalArgumentException() {
        // Case 6: birthDate=null → IllegalArgumentException
        assertThatThrownBy(() -> PrekinderAgePolicy.validate(
            null, REFERENCE_DATE, ACADEMIC_YEAR,
            MINIMUM_MONTHS, MAXIMUM_MONTHS, true, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("La fecha de nacimiento es obligatoria");
    }

    @Test
    void futureBirthDate_throwsAgeNotEligible() {
        // Case 7: birthDate en el futuro → AGE_NOT_ELIGIBLE
        LocalDate futureBirthDate = REFERENCE_DATE.plusDays(1);
        assertThatThrownBy(() -> PrekinderAgePolicy.validate(
            futureBirthDate, REFERENCE_DATE, ACADEMIC_YEAR,
            MINIMUM_MONTHS, MAXIMUM_MONTHS, true, true))
            .isInstanceOf(PrekinderDomainException.class)
            .extracting("code").isEqualTo("AGE_NOT_ELIGIBLE");
    }
}
