package cl.mtn.admitiabff.prekinder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cl.mtn.admitiabff.prekinder.service.PrekinderFlowService.AlumniDeclaration;
import cl.mtn.admitiabff.prekinder.service.PrekinderFlowService.EligibilityDeclaration;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrekinderFlowServiceExtractAlumniYearTest {

    @Test
    void fatherWithYear_returnsFatherYear() {
        EligibilityDeclaration eligibility = new EligibilityDeclaration(
            null, // siblings
            null, // employeeParent
            new AlumniDeclaration("GRADUATED", 2004, "4 Medio", null), // fatherAlumni
            new AlumniDeclaration("GRADUATED", 2010, "4 Medio", null)  // motherAlumni
        );

        Integer year = extract(2026, eligibility);

        assertThat(year).isEqualTo(2004);
    }

    @Test
    void fatherWithoutYear_motherWithYear_returnsMotherYear() {
        EligibilityDeclaration eligibility = new EligibilityDeclaration(
            null,
            null,
            new AlumniDeclaration("NO_ALUMNI", null, null, null), // father not alumni
            new AlumniDeclaration("GRADUATED", 2010, "4 Medio", null)
        );

        Integer year = extract(2026, eligibility);

        assertThat(year).isEqualTo(2010);
    }

    @Test
    void neitherHasYear_returnsNull() {
        EligibilityDeclaration eligibility = new EligibilityDeclaration(
            null,
            null,
            new AlumniDeclaration("NO_ALUMNI", null, null, null),
            new AlumniDeclaration("NO_ALUMNI", null, null, null)
        );

        Integer year = extract(2026, eligibility);

        assertThat(year).isNull();
    }

    @Test
    void yearBelow1900_throws() {
        EligibilityDeclaration eligibility = new EligibilityDeclaration(
            null,
            null,
            new AlumniDeclaration("GRADUATED", 1899, "4 Medio", null),
            null
        );

        assertThatThrownBy(() -> extract(2026, eligibility))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("1899")
            .hasMessageContaining("1900");
    }

    @Test
    void yearAboveAcademicYear_throws() {
        EligibilityDeclaration eligibility = new EligibilityDeclaration(
            null,
            null,
            new AlumniDeclaration("GRADUATED", 2030, "4 Medio", null),
            null
        );

        assertThatThrownBy(() -> extract(2026, eligibility))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("2030")
            .hasMessageContaining("2026");
    }

    @Test
    void nullEligibility_returnsNull() {
        Integer year = extract(2026, null);
        assertThat(year).isNull();
    }

    @Test
    void nullEligibilityRecord_returnsNull() {
        EligibilityDeclaration eligibility = new EligibilityDeclaration(
            List.of(),
            null,
            null,
            null
        );

        Integer year = extract(2026, eligibility);

        assertThat(year).isNull();
    }

    // Delegates to the private static method via a package-visible wrapper
    private Integer extract(int academicYear, EligibilityDeclaration eligibility) {
        return PrekinderFlowServiceExtractAlumniYearTestWrapper.extractAlumniParentYear(eligibility, academicYear);
    }
}
