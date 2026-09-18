package cl.mtn.admitiabff.prekinder.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PrekinderScoringPolicyTest {
    @Test
    void appliesOnlyTheThreeCanonicalWeights() {
        var score = PrekinderScoringPolicy.calculate(
            new BigDecimal("19"), new BigDecimal("19"),
            new BigDecimal("10"), new BigDecimal("20"),
            BigDecimal.ZERO, new BigDecimal("15"));

        assertEquals(new BigDecimal("0.505000"), score.total());
        assertEquals(3, score.normalizedComponents().size());
    }

    @Test
    void rejectsMissingOrOutOfRangeScores() {
        assertThrows(IllegalArgumentException.class,
            () -> PrekinderScoringPolicy.calculate(null, new BigDecimal("19"),
                BigDecimal.TEN, new BigDecimal("20"), BigDecimal.TEN, new BigDecimal("15")));
        assertThrows(IllegalArgumentException.class,
            () -> PrekinderScoringPolicy.calculate(new BigDecimal("20"), new BigDecimal("19"),
                BigDecimal.TEN, new BigDecimal("20"), BigDecimal.TEN, new BigDecimal("15")));
        assertThrows(IllegalArgumentException.class,
            () -> PrekinderScoringPolicy.calculate(BigDecimal.ONE, null,
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN));
    }
}
