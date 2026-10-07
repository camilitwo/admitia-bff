package cl.mtn.admitiabff.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.application.FamilyEntity;
import cl.mtn.admitiabff.domain.common.ApplicationStatus;
import cl.mtn.admitiabff.domain.person.GuardianEntity;
import cl.mtn.admitiabff.domain.person.ParentEntity;
import cl.mtn.admitiabff.domain.person.SupporterEntity;
import cl.mtn.admitiabff.domain.student.StudentEntity;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class SchoolnetExportServiceTest {
    @Test
    void exportsAcceptedApplicationsWithTemplateHeadersAndMappedValues() throws Exception {
        ApplicationRepository repository = mock(ApplicationRepository.class);
        when(repository.findActiveForSchoolnetExport(null, null, List.of(ApplicationStatus.APPROVED))).thenReturn(List.of(application()));
        SchoolnetExportService service = new SchoolnetExportService(repository);

        byte[] bytes = service.exportAcceptedStudents();

        verify(repository).findActiveForSchoolnetExport(null, null, List.of(ApplicationStatus.APPROVED));
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheet("Alumnos");
            assertEquals(52, SchoolnetExportService.HEADERS.size());
            assertEquals(1, sheet.getLastRowNum());
            for (int i = 0; i < SchoolnetExportService.HEADERS.size(); i++) {
                assertEquals(SchoolnetExportService.HEADERS.get(i), sheet.getRow(0).getCell(i).getStringCellValue());
            }
            var row = sheet.getRow(1);
            assertEquals("María Florencia", row.getCell(0).getStringCellValue());
            assertEquals("Montenegro", row.getCell(1).getStringCellValue());
            assertEquals("Reyes", row.getCell(2).getStringCellValue());
            assertEquals("233428825", row.getCell(3).getStringCellValue());
            assertEquals("07-06-2010", row.getCell(4).getStringCellValue());
            assertEquals("Chile", row.getCell(5).getStringCellValue());
            assertEquals("2o Medio", row.getCell(10).getStringCellValue());
            assertEquals("F", row.getCell(11).getStringCellValue());
            assertEquals("00-00-0000", row.getCell(12).getStringCellValue());
            assertEquals("Francisco José", row.getCell(14).getStringCellValue());
            assertEquals("Montenegro", row.getCell(15).getStringCellValue());
            assertEquals("Cruz", row.getCell(16).getStringCellValue());
            assertEquals("108230622", row.getCell(18).getStringCellValue());
            assertEquals("María Loreto Reyes", row.getCell(42).getStringCellValue());
            assertEquals("San", row.getCell(43).getStringCellValue());
            assertEquals("Martín", row.getCell(44).getStringCellValue());
            assertEquals("Montenegro Reyes", row.getCell(46).getStringCellValue());
            assertEquals("2027", row.getCell(50).getStringCellValue());
            assertEquals("reyes.loreto@gmail.com", row.getCell(51).getStringCellValue());
        }
    }

    @Test
    void helperMethodsNormalizeSchoolnetValues() {
        assertEquals("12345678K", SchoolnetExportService.normalizeRut("12.345.678-k"));
        assertEquals("1o Básico", SchoolnetExportService.schoolnetGrade("1_BASICO"));
        assertEquals("M", SchoolnetExportService.schoolnetGender("MALE"));
        assertEquals("", SchoolnetExportService.formatDate(null));

        var name = SchoolnetExportService.splitAdultName("Francisco José Montenegro Cruz");
        assertEquals("Francisco José", name.names());
        assertEquals("Montenegro", name.paternalLastName());
        assertEquals("Cruz", name.maternalLastName());
    }

    @Test
    void emptyExportKeepsOnlyHeaderRow() throws Exception {
        ApplicationRepository repository = mock(ApplicationRepository.class);
        when(repository.findActiveForSchoolnetExport(null, null, List.of(ApplicationStatus.APPROVED))).thenReturn(List.of());
        SchoolnetExportService service = new SchoolnetExportService(repository);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(service.exportAcceptedStudents()))) {
            var sheet = workbook.getSheet("Alumnos");
            assertTrue(sheet.getLastRowNum() == 0);
            assertEquals("[Alumnos]Nombres", sheet.getRow(0).getCell(0).getStringCellValue());
        }
    }

    private static ApplicationEntity application() {
        StudentEntity student = new StudentEntity();
        student.setFirstName("María Florencia");
        student.setPaternalLastName("Montenegro");
        student.setMaternalLastName("Reyes");
        student.setRut("23.342.882-5");
        student.setBirthDate(LocalDate.of(2010, 6, 7));
        student.setPais(null);
        student.setAddress("Camino De La Villa 676");
        student.setComuna("LO BARNECHEA");
        student.setCurrentSchool("Tailandia");
        student.setGradeApplied("2_MEDIO");
        student.setGender("FEMALE");

        ParentEntity father = parent("Francisco José Montenegro Cruz", "10.823.062-2", "fmontenegro.eov@gmail.com", "999404915");
        ParentEntity mother = parent("María Loreto Reyes San Martín", "12.403.333-0", "reyes.loreto@gmail.com", "999404915");

        GuardianEntity guardian = new GuardianEntity();
        guardian.setFullName("María Loreto Reyes San Martín");
        guardian.setRut("12.403.333-0");
        guardian.setEmail("reyes.loreto@gmail.com");
        guardian.setPhone("999404915");

        SupporterEntity supporter = new SupporterEntity();
        supporter.setFullName("Francisco José Montenegro Cruz");
        supporter.setRut("10.823.062-2");
        supporter.setEmail("fmontenegro.eov@gmail.com");
        supporter.setPhone("999404915");

        UserEntity user = new UserEntity();
        user.setEmail("familia@example.cl");

        FamilyEntity family = new FamilyEntity();
        family.setId(70L);

        ApplicationEntity application = new ApplicationEntity();
        application.setId(1L);
        application.setFamily(family);
        application.setStudent(student);
        application.setFather(father);
        application.setMother(mother);
        application.setGuardian(guardian);
        application.setSupporter(supporter);
        application.setApplicantUser(user);
        application.setStatus(ApplicationStatus.APPROVED);
        application.setAcademicYear(2027);
        application.setSubmissionDate(LocalDateTime.now());
        return application;
    }

    private static ParentEntity parent(String fullName, String rut, String email, String phone) {
        ParentEntity parent = new ParentEntity();
        parent.setFullName(fullName);
        parent.setRut(rut);
        parent.setEmail(email);
        parent.setPhone(phone);
        parent.setAddress("Camino De La Villa 676");
        return parent;
    }
}
