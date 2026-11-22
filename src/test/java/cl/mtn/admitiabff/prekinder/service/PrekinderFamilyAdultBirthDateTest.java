package cl.mtn.admitiabff.prekinder.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PrekinderFamilyAdultBirthDateTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void birthDateSurvivesTheEncryptedJsonRoundTrip() throws Exception {
        var adult = new PrekinderFlowService.FamilyAdultDetails("Ana Pérez", "12.345.678-5", "a@x.cl", "+56911112222",
            "Calle 1", null, LocalDate.of(1988, 5, 17));
        String json = mapper.writeValueAsString(adult);
        assertEquals(LocalDate.of(1988, 5, 17), mapper.readValue(json, PrekinderFlowService.FamilyAdultDetails.class).birthDate());
    }

    @Test
    void applicationsStoredBeforeTheFieldExistedStillLoad() throws Exception {
        String legacy = "{\"fullName\":\"Ana\",\"rut\":\"1-9\",\"email\":\"a@x.cl\",\"phone\":\"1\",\"address\":\"c\"}";
        assertNull(mapper.readValue(legacy, PrekinderFlowService.FamilyAdultDetails.class).birthDate());
    }
}
