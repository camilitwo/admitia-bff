package cl.mtn.admitiabff.prekinder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import java.util.List;
import java.util.UUID;
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
    void genericProfessionalAccountUsesDatabaseInstrumentAuthorizations() {
        var actor = new PrekinderActor(UUID.randomUUID(), 73L, "PREKINDER_PROFESSIONAL");

        assertThat(PrekinderEvaluatorService.roleAllowsInstrument(actor, "ACADEMIC")).isTrue();
        assertThat(PrekinderEvaluatorService.roleAllowsInstrument(actor, "PSYCHOLOGY")).isTrue();
    }

    @Test
    void specializedEvaluatorRolesOnlyAllowTheirOwnInstrument() {
        List<String> roles = List.of(
            "PK_EVALUATOR_ACADEMIC", "PK_EVALUATOR_PSYCHOMOTOR", "PK_EVALUATOR_PSYCHOLOGY",
            "PK_EVALUATOR_ENTRY_INDICATORS", "PK_EVALUATOR_GROUP_OBSERVATION",
            "PK_EVALUATOR_LEARNING_SUPPORT", "PK_EVALUATOR_DAP");
        List<String> instruments = List.of(
            "ACADEMIC", "PSYCHOMOTOR", "PSYCHOLOGY", "ENTRY_INDICATORS",
            "GROUP_OBSERVATION", "LEARNING_SUPPORT", "DAP");

        for (int index = 0; index < roles.size(); index++) {
            var actor = new PrekinderActor(UUID.randomUUID(), 100L + index, roles.get(index));
            assertThat(PrekinderEvaluatorService.roleAllowsInstrument(actor, instruments.get(index))).isTrue();
            assertThat(PrekinderEvaluatorService.roleAllowsInstrument(
                actor, instruments.get((index + 1) % instruments.size()))).isFalse();
        }
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
