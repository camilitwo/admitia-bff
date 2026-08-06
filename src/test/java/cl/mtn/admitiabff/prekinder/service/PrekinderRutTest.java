package cl.mtn.admitiabff.prekinder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PrekinderRutTest {
    @Test
    void normalizesDotsDashAndVerifier() {
        assertThat(PrekinderRut.normalize("12.345.678-5")).isEqualTo("12345678-5");
    }

    @Test
    void acceptsUppercaseKVerifier() {
        assertThat(PrekinderRut.normalize("6.000.000-k")).isEqualTo("6000000-K");
    }

    @Test
    void rejectsInvalidVerifierAndMalformedValues() {
        assertThatThrownBy(() -> PrekinderRut.normalize("12.345.678-9"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("El dígito verificador del RUT no es válido");
        assertThatThrownBy(() -> PrekinderRut.normalize("123"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("El RUT del postulante no es válido");
    }
}
