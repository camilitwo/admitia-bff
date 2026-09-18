package cl.mtn.admitiabff.prekinder.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Códigos estables del contrato Prekínder. Los valores de negocio variables
 * pertenecen a la configuración versionada del proceso, no a esta clase.
 */
public final class PrekinderPolicyCodes {
    private PrekinderPolicyCodes() {}

    public static final String RESULT_CHANNEL_EMAIL_ONLY = "EMAIL_ONLY";
    public static final String PAYMENT_APPLICATION = "APPLICATION";
    public static final String PAYMENT_INCORPORATION = "INCORPORATION";

    public static final String ENTRY_INDICATORS = "ENTRY_INDICATORS";
    public static final String ACADEMIC = "ACADEMIC";
    public static final String PSYCHOLOGY = "PSYCHOLOGY";
    public static final String PSYCHOMOTOR = "PSYCHOMOTOR";
    public static final String GROUP_OBSERVATION = "GROUP_OBSERVATION";
    public static final String FAMILY_INTERVIEW = "FAMILY_INTERVIEW";
    public static final String LEARNING_SUPPORT = "LEARNING_SUPPORT";
    public static final String DAP = "DAP";

    public static final List<String> REQUIRED_INSTRUMENTS = List.of(
        ENTRY_INDICATORS, ACADEMIC, PSYCHOLOGY, PSYCHOMOTOR,
        GROUP_OBSERVATION, FAMILY_INTERVIEW
    );
    public static final Set<String> CONDITIONAL_INSTRUMENTS = Set.of(LEARNING_SUPPORT, DAP);
    public static final Set<String> SCORING_INSTRUMENTS = Set.of(ACADEMIC, PSYCHOLOGY, PSYCHOMOTOR);

    public static final BigDecimal ACADEMIC_WEIGHT = new BigDecimal("0.34");
    public static final BigDecimal PSYCHOLOGY_WEIGHT = new BigDecimal("0.33");
    public static final BigDecimal PSYCHOMOTOR_WEIGHT = new BigDecimal("0.33");

    public static final Set<String> TERMINAL_APPLICATION_STATES = Set.of(
        "ENROLLED", "DECLINED", "EXPIRED", "WITHDRAWN", "CANCELLED", "INVALIDATED", "NOT_ADMITTED"
    );
}
