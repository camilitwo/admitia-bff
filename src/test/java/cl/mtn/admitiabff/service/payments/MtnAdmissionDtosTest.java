package cl.mtn.admitiabff.service.payments;

import static org.junit.jupiter.api.Assertions.assertFalse;

import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.AdmissionRequest;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.ChargeRequest;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.StudentRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class MtnAdmissionDtosTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void omitsCourseFieldsWhenApplicationHasNoSelectedCourse() throws Exception {
        AdmissionRequest admission = new AdmissionRequest("12345678", "5", "Juan", null, null, null,
            "Santiago", null, List.of(new StudentRequest("11111111", "1", "Ana", null)));
        ChargeRequest charge = new ChargeRequest("12345678", "5", "Juan", null, "11111111", "1", "Ana",
            null, new BigDecimal("50000"), "CLP", "2026-07-24", "Matrícula", "ADMITIA-1");

        String admissionJson = objectMapper.writeValueAsString(admission);
        String chargeJson = objectMapper.writeValueAsString(charge);
        assertFalse(admissionJson.contains("codCurso"));
        assertFalse(admissionJson.contains("phone"));
        assertFalse(chargeJson.contains("alumno_curso"));
        assertFalse(chargeJson.contains("apoderado_fono"));
    }
}
