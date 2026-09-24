package cl.mtn.admitiabff.prekinder.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PrekinderPublicationWorkerTest {
    @Test
    void sendsToBothParentsWithoutDuplicatingThePrimaryContact() {
        var recipients = PrekinderPublicationWorker.parentEmails(Map.of(
            "fatherEmail", "padre@example.cl",
            "motherEmail", "madre@example.cl",
            "familyEmail", "PADRE@example.cl"));

        assertThat(recipients).containsExactly("padre@example.cl", "madre@example.cl");
    }

    @Test
    void ignoresFamilyEmailForScheduleNotifications() {
        var recipients = PrekinderPublicationWorker.parentEmails(Map.of(
            "familyEmail", "familia@example.cl",
            "fatherEmail", "",
            "motherEmail", "madre@example.cl"));

        assertThat(recipients).containsExactly("madre@example.cl");
    }

    @Test
    void scheduleEmailContainsDateTimeRoomAndGroup() {
        String body = PrekinderPublicationWorker.scheduleEmailBody(Map.of(
            "startsAt", "2026-08-10T12:30:00Z",
            "endsAt", "2026-08-10T13:00:00Z",
            "roomName", "Sala Arrayán",
            "roomCode", "A-12",
            "groupCode", "M3-04",
            "stage", "GROUP_3"), false);

        assertThat(body)
            .contains("lunes 10 de agosto de 2026")
            .contains("08:30 a 09:00 horas")
            .contains("Sala Arrayán (A-12)")
            .contains("M3-04")
            .contains("Modalidad:</strong> Presencial")
            .contains("Evaluación académica, psicológica e indicadores de ingreso");
    }
}
