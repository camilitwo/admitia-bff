package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.application.AdmissionCycleEntity;
import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.common.AdmissionCycleStatus;
import cl.mtn.admitiabff.repository.AdmissionCycleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdmissionCycleGuard {
    private final AdmissionCycleRepository cycleRepository;
    private final JdbcTemplate jdbcTemplate;

    public AdmissionCycleGuard(AdmissionCycleRepository cycleRepository, JdbcTemplate jdbcTemplate) {
        this.cycleRepository = cycleRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Crea el ciclo si todavía no existe y mantiene su bloqueo hasta el commit. */
    public void assertOpenForCreate(Integer academicYear) {
        requireYear(academicYear);
        jdbcTemplate.update("""
                INSERT INTO admission_cycles (academic_year, status, created_at, updated_at)
                VALUES (?, 'OPEN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (academic_year) DO NOTHING
                """, academicYear);
        AdmissionCycleEntity cycle = cycleRepository.findByAcademicYearForUpdate(academicYear)
                .orElseThrow(() -> new IllegalStateException("No fue posible resolver el ciclo de admisión"));
        requireOpen(cycle);
    }

    /** Ciclos antiguos sin registro conservan compatibilidad; un ciclo registrado siempre se respeta. */
    public void assertOpen(ApplicationEntity application) {
        Integer academicYear = application.getAcademicYear();
        requireYear(academicYear);
        cycleRepository.findByAcademicYearForUpdate(academicYear).ifPresent(this::requireOpen);
    }

    private void requireOpen(AdmissionCycleEntity cycle) {
        if (cycle.getStatus() != AdmissionCycleStatus.OPEN) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "El proceso de admisión " + cycle.getAcademicYear() + " está cerrado y solo admite consultas");
        }
    }

    private void requireYear(Integer academicYear) {
        if (academicYear == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "La postulación no tiene año académico; debe corregirse antes de modificarla");
        }
    }
}
