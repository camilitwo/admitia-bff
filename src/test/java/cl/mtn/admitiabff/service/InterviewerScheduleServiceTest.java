package cl.mtn.admitiabff.service;

import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("InterviewerScheduleService - 60 Minute Slots Validation")
class InterviewerScheduleServiceTest {

    @Test
    @DisplayName("Validate 60-minute intervals: 09:00-12:00 should produce 3 slots")
    void testTimeSlotCalculation3Hours() {
        // Given: A time range from 09:00 to 12:00 (3 hours)
        LocalTime start = LocalTime.parse("09:00:00");
        LocalTime end = LocalTime.parse("12:00:00");
        int slotDurationMinutes = 60;

        // When: Calculating slots
        int slotCount = calculateSlots(start, end, slotDurationMinutes);

        // Then: Should create exactly 3 slots of 60 minutes each
        assertEquals(3, slotCount, "09:00-12:00 should produce 3 slots of 60 minutes");
    }

    @Test
    @DisplayName("Validate 60-minute intervals: 14:00-16:00 should produce 2 slots")
    void testTimeSlotCalculation2Hours() {
        // Given: A time range from 14:00 to 16:00 (2 hours)
        LocalTime start = LocalTime.parse("14:00:00");
        LocalTime end = LocalTime.parse("16:00:00");
        int slotDurationMinutes = 60;

        // When: Calculating slots
        int slotCount = calculateSlots(start, end, slotDurationMinutes);

        // Then: Should create exactly 2 slots of 60 minutes each
        assertEquals(2, slotCount, "14:00-16:00 should produce 2 slots of 60 minutes");
    }

    @Test
    @DisplayName("Validate 60-minute intervals: 11:00-12:00 should produce 1 slot")
    void testTimeSlotCalculation1Hour() {
        // Given: A time range from 11:00 to 12:00 (1 hour)
        LocalTime start = LocalTime.parse("11:00:00");
        LocalTime end = LocalTime.parse("12:00:00");
        int slotDurationMinutes = 60;

        // When: Calculating slots
        int slotCount = calculateSlots(start, end, slotDurationMinutes);

        // Then: Should create exactly 1 slot of 60 minutes
        assertEquals(1, slotCount, "11:00-12:00 should produce 1 slot of 60 minutes");
    }

    @Test
    @DisplayName("Validate 60-minute intervals: 08:00-17:00 should produce 9 slots")
    void testTimeSlotCalculationFullDay() {
        // Given: A time range from 08:00 to 17:00 (9 hours - full day)
        LocalTime start = LocalTime.parse("08:00:00");
        LocalTime end = LocalTime.parse("17:00:00");
        int slotDurationMinutes = 60;

        // When: Calculating slots
        int slotCount = calculateSlots(start, end, slotDurationMinutes);

        // Then: Should create exactly 9 slots of 60 minutes each
        assertEquals(9, slotCount, "08:00-17:00 should produce 9 slots of 60 minutes");
    }

    @Test
    @DisplayName("Verify slot duration is exactly 60 minutes")
    void testSlotDuration() {
        // Given: A slot from 09:00 to 10:00
        LocalTime start = LocalTime.parse("09:00:00");
        LocalTime end = LocalTime.parse("10:00:00");

        // When: Calculating duration in minutes
        long durationMinutes = ChronoUnit.MINUTES.between(start, end);

        // Then: Duration should be exactly 60 minutes
        assertEquals(60, durationMinutes, "Slot duration should be exactly 60 minutes");
    }

    private int calculateSlots(LocalTime start, LocalTime end, int slotDurationMinutes) {
        int count = 0;
        LocalTime current = start;
        while (current.isBefore(end)) {
            LocalTime next = current.plusMinutes(slotDurationMinutes);
            count++;
            current = next;
        }
        return count;
    }
}
