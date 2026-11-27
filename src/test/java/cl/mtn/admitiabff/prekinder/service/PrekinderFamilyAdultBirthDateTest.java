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
        var adult = new PrekinderFlowService.FamilyAdultDetails("Ana María Pérez Soto", "Ana María", "Pérez", "Soto",
            "MARRIED", "12.345.678-5", "a@x.cl", "+56911112222", "Calle 1", null, LocalDate.of(1988, 5, 17));
        String json = mapper.writeValueAsString(adult);
        var restored = mapper.readValue(json, PrekinderFlowService.FamilyAdultDetails.class);
        assertEquals(LocalDate.of(1988, 5, 17), restored.birthDate());
        assertEquals("Ana María", restored.firstName());
        assertEquals("Pérez", restored.paternalLastName());
        assertEquals("Soto", restored.maternalLastName());
        assertEquals("MARRIED", restored.maritalStatus());
    }

    @Test
    void applicationsStoredBeforeTheFieldExistedStillLoad() throws Exception {
        String legacy = "{\"fullName\":\"Ana\",\"rut\":\"1-9\",\"email\":\"a@x.cl\",\"phone\":\"1\",\"address\":\"c\"}";
        var restored = mapper.readValue(legacy, PrekinderFlowService.FamilyAdultDetails.class);
        assertEquals("Ana", restored.fullName());
        assertNull(restored.firstName());
        assertNull(restored.birthDate());
    }

    @Test
    void parentsSeparatedDefaultsForLegacyAndSurvivesNewJson() throws Exception {
        assertEquals(false, mapper.readValue("{}", PrekinderFlowService.ApplicationDetails.class).parentsSeparated());
        assertEquals(true, mapper.readValue("{\"parentsSeparated\":true}", PrekinderFlowService.ApplicationDetails.class).parentsSeparated());
    }
}
