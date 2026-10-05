package cl.mtn.admitiabff.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
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
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApplicationsControllerSchoolnetExportTest {
    @Test
    void schoolnetExportReturnsXlsxAttachment() throws Exception {
        ApplicationRepository applicationRepository = mock(ApplicationRepository.class);
        when(applicationRepository.findActiveForSchoolnetExport(ApplicationStatus.APPROVED)).thenReturn(List.of());
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
                org.hamcrest.Matchers.containsString("schoolnet_alumnos_aceptados_")));
    }
}
