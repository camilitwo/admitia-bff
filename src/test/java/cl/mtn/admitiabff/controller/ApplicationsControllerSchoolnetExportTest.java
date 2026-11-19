package cl.mtn.admitiabff.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.mtn.admitiabff.service.ApplicationService;
import cl.mtn.admitiabff.service.AuthService;
import cl.mtn.admitiabff.service.SchoolnetExportService;
import cl.mtn.admitiabff.service.payments.PaymentService;
import cl.mtn.admitiabff.domain.common.ApplicationStatus;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import java.util.List;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApplicationsControllerSchoolnetExportTest {
    @Test
    void schoolnetExportReturnsXlsxAttachment() throws Exception {
        ApplicationRepository applicationRepository = mock(ApplicationRepository.class);
        when(applicationRepository.findActiveForSchoolnetExport(null, null, Arrays.stream(ApplicationStatus.values()).filter(value -> value != ApplicationStatus.ARCHIVED).toList())).thenReturn(List.of());
        SchoolnetExportService schoolnetExportService = new SchoolnetExportService(applicationRepository);
        var mvc = MockMvcBuilders.standaloneSetup(new ApplicationsController(
            null,
            null,
            null,
            schoolnetExportService
        )).build();

        mvc.perform(get("/api/applications/export/schoolnet"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                org.hamcrest.Matchers.containsString("schoolnet_alumnos_postulantes_")));
        verify(applicationRepository).findActiveForSchoolnetExport(null, null,
            Arrays.stream(ApplicationStatus.values()).filter(value -> value != ApplicationStatus.ARCHIVED).toList());
    }
    @Test
    void explicitStatusFilterAndProcessArePreserved() throws Exception {
        var service = mock(SchoolnetExportService.class);
        when(service.exportStudents(2027, "KIV-2027-02", List.of(ApplicationStatus.APPROVED)))
            .thenReturn(new byte[] {1});
        var mvc = MockMvcBuilders.standaloneSetup(new ApplicationsController(null, null, null, service)).build();

        mvc.perform(get("/api/applications/export/schoolnet")
                .param("academicYear", "2027")
                .param("processCode", "KIV-2027-02")
                .param("statuses", "APPROVED"))
            .andExpect(status().isOk());

        verify(service).exportStudents(2027, "KIV-2027-02", List.of(ApplicationStatus.APPROVED));
    }

}
