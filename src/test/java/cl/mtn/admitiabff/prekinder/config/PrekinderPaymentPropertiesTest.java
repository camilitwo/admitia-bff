package cl.mtn.admitiabff.prekinder.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PrekinderPaymentPropertiesTest {
    @Test
    void acceptsIndependentPositiveFee() {
        var properties = new PrekinderPaymentProperties(true, new BigDecimal("65000"),
            "Postulación Prekínder", "CLP", 3, "ADMITIA-PK");

        assertThatCode(properties::validateForUse).doesNotThrowAnyException();
    }

    @Test
    void rejectsDisabledOrZeroFee() {
        assertThatThrownBy(() -> new PrekinderPaymentProperties(false, new BigDecimal("65000"),
            "Postulación Prekínder", "CLP", 3, "ADMITIA-PK").validateForUse())
            .hasMessageContaining("no están habilitados");
        assertThatThrownBy(() -> new PrekinderPaymentProperties(true, BigDecimal.ZERO,
            "Postulación Prekínder", "CLP", 3, "ADMITIA-PK").validateForUse())
            .hasMessageContaining("mayor a cero");
    }
}
