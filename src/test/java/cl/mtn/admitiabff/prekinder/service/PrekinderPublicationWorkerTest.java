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
    void scheduleEmailContainsDateTimeRoomAndGroup() {
        String body = PrekinderPublicationWorker.scheduleEmailBody(Map.of(
            "startsAt", "2026-08-10T12:30:00Z",
            "endsAt", "2026-08-10T13:00:00Z",
            "roomName", "Sala Arrayán",
            "roomCode", "A-12",
            "groupCode", "M3-04"), false);

        assertThat(body)
            .contains("lunes 10 de agosto de 2026")
            .contains("08:30 a 09:00 horas")
            .contains("Sala Arrayán (A-12)")
            .contains("M3-04");
    }
}
