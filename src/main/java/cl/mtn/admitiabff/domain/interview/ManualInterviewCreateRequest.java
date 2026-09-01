package cl.mtn.admitiabff.domain.interview;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

public record ManualInterviewCreateRequest(
    @NotNull(message = "Selecciona una postulación") Long applicationId,
    @NotNull(message = "Selecciona el primer entrevistador") Long interviewerId,
    @NotNull(message = "Selecciona el segundo entrevistador") Long secondInterviewerId,
    @NotNull(message = "Selecciona una fecha") LocalDate scheduledDate,
    @NotNull(message = "Selecciona una hora") LocalTime scheduledTime,
    @NotNull(message = "Indica la duración")
    @Min(value = 15, message = "La duración mínima es 15 minutos")
    @Max(value = 240, message = "La duración máxima es 240 minutos") Integer duration,
    @NotBlank(message = "Selecciona una modalidad") String mode,
    String location,
    @NotBlank(message = "Explica el motivo del ingreso excepcional")
    @Size(min = 5, max = 1000, message = "El motivo debe tener entre 5 y 1000 caracteres") String reason,
    boolean confirmWarnings
) {
}
