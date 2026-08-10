package cl.mtn.admitiabff.prekinder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PrekinderFrontendContractTest {
    @Test
    void frontendProfileAliasesResolveToCanonicalInstruments() {
        assertThat(PrekinderEvaluatorService.normalizeInstrument("INDICATORS")).isEqualTo("ENTRY_INDICATORS");
        assertThat(PrekinderEvaluatorService.normalizeInstrument("SUPPORT")).isEqualTo("LEARNING_SUPPORT");
        assertThat(PrekinderEvaluatorService.normalizeInstrument("PREKINDER_OBSERVER")).isEqualTo("GROUP_OBSERVATION");
        assertThat(PrekinderEvaluatorService.normalizeInstrument("PK_EVALUATOR_ACADEMIC")).isEqualTo("ACADEMIC");
    }

    @Test
    void attendancePresentationDoesNotOverloadAbsence() {
        var late = PrekinderControlTowerService.AttendanceState.from("LATE");
        var couldNotEnter = PrekinderControlTowerService.AttendanceState.from("COULD_NOT_ENTER");
        assertThat(late.persisted()).isEqualTo("ATTENDED");
        assertThat(late.detail()).isEqualTo("LATE");
        assertThat(couldNotEnter.persisted()).isEqualTo("ABSENT");
        assertThat(couldNotEnter.detail()).isEqualTo("COULD_NOT_ENTER");
        assertThat(PrekinderControlTowerService.AttendanceState.presented("ATTENDED", "LATE")).isEqualTo("LATE");
    }

    @Test
    void unknownAttendanceStateFailsClosed() {
        assertThatThrownBy(() -> PrekinderControlTowerService.AttendanceState.from("MAYBE"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
