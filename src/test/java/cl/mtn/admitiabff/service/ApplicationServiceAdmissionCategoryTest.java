package cl.mtn.admitiabff.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cl.mtn.admitiabff.domain.student.StudentEntity;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApplicationServiceAdmissionCategoryTest {
    @Test
    void mapsAlumniPreferenceToCanonicalColumns() {
        StudentEntity student = new StudentEntity();

        ApplicationService.applyAdmissionCategory(student,
            Map.of("admissionPreference", "HIJO_EX_ALUMNO", "alumniParentYear", 2004), Map.of());

        assertThat(student.getAdmissionPreference()).isEqualTo("HIJO_EX_ALUMNO");
        assertThat(student.isAlumniChild()).isTrue();
        assertThat(student.isEmployeeChild()).isFalse();
        assertThat(student.getAlumniParentYear()).isEqualTo(2004);
    }

    @Test
    void supportsLegacyStudentAdmissionPreferencePayload() {
        StudentEntity student = new StudentEntity();

        ApplicationService.applyAdmissionCategory(student, Map.of(),
            Map.of("studentAdmissionPreference", "HIJO_EX_ALUMNO"));

        assertThat(student.getAdmissionPreference()).isEqualTo("HIJO_EX_ALUMNO");
        assertThat(student.isAlumniChild()).isTrue();
    }

    @Test
    void switchingToRegularClearsContradictoryAlumniData() {
        StudentEntity student = new StudentEntity();
        student.setAdmissionPreference("HIJO_EX_ALUMNO");
        student.setAlumniChild(true);
        student.setAlumniParentYear(1999);

        ApplicationService.applyAdmissionCategory(student, Map.of("admissionPreference", "NINGUNA"), Map.of());

        assertThat(student.getAdmissionPreference()).isEqualTo("NINGUNA");
        assertThat(student.isAlumniChild()).isFalse();
        assertThat(student.getAlumniParentYear()).isNull();
    }

    @Test
    void rejectsUnknownPreference() {
        assertThatThrownBy(() -> ApplicationService.applyAdmissionCategory(
            new StudentEntity(), Map.of("admissionPreference", "OTRA"), Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Preferencia de admisión inválida");
    }

    @Test
    void mapsSiblingInformationToStudent() {
        StudentEntity student = new StudentEntity();

        ApplicationService.applySiblingInformation(student, Map.of(
            "hasSiblingsInSchool", true,
            "siblingsInSchoolDetails", "  María Pérez, 2 Básico  "));

        assertThat(student.isHasSiblingsInSchool()).isTrue();
        assertThat(student.getSiblingsInSchoolDetails()).isEqualTo("María Pérez, 2 Básico");
    }

    @Test
    void selectingNoClearsPreviousSiblingDetails() {
        StudentEntity student = new StudentEntity();
        student.setHasSiblingsInSchool(true);
        student.setSiblingsInSchoolDetails("María Pérez, 2 Básico");

        ApplicationService.applySiblingInformation(student, Map.of("hasSiblingsInSchool", false));

        assertThat(student.isHasSiblingsInSchool()).isFalse();
        assertThat(student.getSiblingsInSchoolDetails()).isNull();
    }
}
