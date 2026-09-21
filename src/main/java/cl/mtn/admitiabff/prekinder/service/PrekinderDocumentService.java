package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import cl.mtn.admitiabff.service.VercelBlobService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderDocumentService {
    private static final Logger log = LoggerFactory.getLogger(PrekinderDocumentService.class);
    private final NamedParameterJdbcTemplate jdbc;
    private final PrekinderAccessService access;
    private final VercelBlobService blobs;
    private final Path localRoot;
    private final PrekinderApplicationStateService applicationStates;

    public PrekinderDocumentService(@Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc,
        PrekinderAccessService access, VercelBlobService blobs,
        PrekinderApplicationStateService applicationStates,
        @Value("${app.uploads-dir:uploads}") String uploadsDir) {
        this.jdbc = jdbc; this.access = access; this.blobs = blobs;
        this.applicationStates = applicationStates;
        this.localRoot = Path.of(uploadsDir).toAbsolutePath().resolve("prekinder");
    }

    public DocumentView upload(UUID applicationId, String category, MultipartFile file) throws IOException {
        PrekinderActor actor = access.requireActor();
        assertAccess(applicationId, actor);
        String normalizedCategory = normalizeCategory(category);
        validateUploadScope(applicationId, normalizedCategory, actor);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Selecciona un documento");
        if (file.getSize() > 20L * 1024 * 1024) throw new IllegalArgumentException("El archivo supera 20 MB");
        String mediaType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        if (!(mediaType.equals("application/pdf") || mediaType.startsWith("image/"))) {
            throw new IllegalArgumentException("Sólo se admiten documentos PDF o imágenes");
        }
        UUID id = UUID.randomUUID();
        byte[] bytes = file.getBytes();
        String extension = extension(file.getOriginalFilename());
        String objectKey = "prekinder/" + applicationId + "/" + id + extension;
        String storageKey;
        if (blobs.isEnabled()) {
            try {
                storageKey = blobs.upload(bytes, objectKey, mediaType).url;
            } catch (RuntimeException exception) {
                log.warn("event=prekinder_document_upload_failed applicationId={} category={} storage=blob",
                    applicationId, normalizedCategory, exception);
                throw new PrekinderDomainException("DOCUMENT_UPLOAD_FAILED",
                    "No fue posible almacenar el documento; inténtalo nuevamente",
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
            }
        } else {
            try {
                Files.createDirectories(localRoot.resolve(applicationId.toString()));
                Path target = localRoot.resolve(applicationId.toString()).resolve(id + extension);
                Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
                storageKey = target.toString();
            } catch (IOException exception) {
                log.warn("event=prekinder_document_upload_failed applicationId={} category={} storage=local",
                    applicationId, normalizedCategory, exception);
                throw exception;
            }
        }
        List<UUID> previous = jdbc.queryForList("""
            SELECT document_id FROM document_metadata
             WHERE application_id = :applicationId AND category = :category
               AND review_status <> 'REPLACED'
             ORDER BY created_at DESC LIMIT 1
            """, Map.of("applicationId", applicationId, "category", normalizedCategory), UUID.class);
        UUID replacedDocumentId = previous.isEmpty() ? null : previous.getFirst();
        if (replacedDocumentId != null) {
            jdbc.update("UPDATE document_metadata SET review_status = 'REPLACED', version = version + 1 WHERE document_id = :id",
                Map.of("id", replacedDocumentId));
        }
        boolean restricted = normalizedCategory.startsWith("INCLUSION_")
            || List.of("DIAGNOSTIC", "DAP", "CONSENT").contains(normalizedCategory);
        jdbc.update("""
            INSERT INTO document_metadata(document_id, application_id, category, storage_key, media_type,
                size_bytes, sha256, scan_status, restricted, uploaded_by, replaces_document_id)
            VALUES (:id, :applicationId, :category, :storageKey, :mediaType, :size, :sha256,
                'PENDING', :restricted, :actorId, :replacesDocumentId)
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("id", id).addValue("applicationId", applicationId).addValue("category", normalizedCategory)
            .addValue("storageKey", storageKey).addValue("mediaType", mediaType).addValue("size", file.getSize())
            .addValue("sha256", sha256(bytes)).addValue("restricted", restricted)
            .addValue("actorId", actor.id()).addValue("replacesDocumentId", replacedDocumentId));
        applicationStates.refresh(applicationId, actor.id());
        log.info("event=prekinder_document_uploaded applicationId={} documentId={} category={} sizeBytes={}",
            applicationId, id, normalizedCategory, file.getSize());
        return document(id);
    }

    public DocumentView review(UUID documentId, String decision, String reason, long expectedVersion) {
        PrekinderActor actor = access.requireAdmin();
        String normalized = decision == null ? "" : decision.trim().toUpperCase();
        if (!List.of("APPROVED", "REJECTED").contains(normalized))
            throw new IllegalArgumentException("La revisión debe ser APPROVED o REJECTED");
        if ("REJECTED".equals(normalized) && (reason == null || reason.isBlank()))
            throw new IllegalArgumentException("El rechazo documental requiere motivo");
        int updated = jdbc.update("""
            UPDATE document_metadata SET review_status = :decision, review_reason = :reason,
                reviewed_by = :actorId, reviewed_at = now(), version = version + 1
             WHERE document_id = :id AND version = :version AND review_status = 'PENDING'
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("id", documentId).addValue("decision", normalized)
            .addValue("reason", reason == null ? null : reason.trim()).addValue("actorId", actor.id())
            .addValue("version", expectedVersion));
        if (updated != 1) throw new VersionConflictException("El documento cambió o ya fue revisado");
        UUID applicationId = jdbc.queryForObject("SELECT application_id FROM document_metadata WHERE document_id = :id",
            Map.of("id", documentId), UUID.class);
        applicationStates.refresh(applicationId, actor.id());
        return document(documentId);
    }

    public List<DocumentView> list(UUID applicationId) {
        PrekinderActor actor = access.requireActor();
        assertAccess(applicationId, actor);
        return jdbc.query("""
            SELECT document_id, application_id, category, media_type, size_bytes, scan_status, restricted,
                   review_status, review_reason, version, created_at
              FROM document_metadata WHERE application_id = :id ORDER BY created_at DESC
            """, Map.of("id", applicationId), (rs, row) -> new DocumentView(rs.getObject("document_id", UUID.class),
                rs.getObject("application_id", UUID.class), rs.getString("category"), rs.getString("media_type"),
                rs.getLong("size_bytes"), rs.getString("scan_status"), rs.getBoolean("restricted"),
                rs.getString("review_status"), rs.getString("review_reason"), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant()));
    }

    public ResponseEntity<ByteArrayResource> download(UUID documentId) throws IOException {
        PrekinderActor actor = access.requireActor();
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT application_id, storage_key, media_type FROM document_metadata WHERE document_id = :id
            """, Map.of("id", documentId));
        assertAccess((UUID) row.get("application_id"), actor);
        String key = String.valueOf(row.get("storage_key"));
        byte[] data = key.startsWith("http://") || key.startsWith("https://") ? blobs.download(key) : Files.readAllBytes(Path.of(key));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(String.valueOf(row.get("media_type"))))
            .header("Content-Disposition", "inline; filename=prekinder-" + documentId)
            .body(new ByteArrayResource(data));
    }

    private void assertAccess(UUID applicationId, PrekinderActor actor) {
        if (List.of("ADMIN", "COORDINATOR", "CYCLE_DIRECTOR", "PK_ADMIN", "PK_COORDINATOR").contains(actor.role())) return;
        Long count = jdbc.queryForObject("""
            SELECT count(*) FROM applications a JOIN applicants ap ON ap.applicant_id = a.applicant_id
              JOIN families f ON f.family_id = ap.family_id
             WHERE a.application_id = :applicationId AND f.external_reference = :actorId
            """, Map.of("applicationId", applicationId, "actorId", actor.id().toString()), Long.class);
        if (count == null || count == 0) throw PrekinderDomainException.forbidden("NOT_ASSIGNED", "Documento no autorizado");
    }

    private void validateUploadScope(UUID applicationId, String category, PrekinderActor actor) {
        if (List.of("ADMIN", "COORDINATOR", "CYCLE_DIRECTOR", "PK_ADMIN", "PK_COORDINATOR").contains(actor.role())) return;
        String status = jdbc.queryForObject("SELECT status FROM applications WHERE application_id = :id",
            Map.of("id", applicationId), String.class);
        if (List.of("PENDING_SEGMENT_VALIDATION", "PENDING_PAYMENT", "FORM_PENDING").contains(status)) return;
        if ("REQUIRES_INFORMATION".equals(status)) {
            Long allowed = jdbc.queryForObject("""
                SELECT count(*) FROM application_correction_requests request
                 WHERE request.application_id = :id AND request.status = 'OPEN'
                   AND request.allowed_document_categories ? :category
                """, Map.of("id", applicationId, "category", category), Long.class);
            if (allowed != null && allowed > 0) return;
        }
        throw PrekinderDomainException.forbidden("DOCUMENT_UPLOAD_LOCKED",
            "La categoría documental no está habilitada para modificación");
    }

    private DocumentView document(UUID id) {
        return jdbc.queryForObject("""
            SELECT document_id, application_id, category, media_type, size_bytes, scan_status, restricted,
                   review_status, review_reason, version, created_at
              FROM document_metadata WHERE document_id = :id
            """, Map.of("id", id), (rs, row) -> new DocumentView(id, rs.getObject("application_id", UUID.class),
                rs.getString("category"), rs.getString("media_type"), rs.getLong("size_bytes"),
                rs.getString("scan_status"), rs.getBoolean("restricted"), rs.getString("review_status"),
                rs.getString("review_reason"), rs.getLong("version"), rs.getTimestamp("created_at").toInstant()));
    }

    private static String normalizeCategory(String value) {
        String normalized = value == null ? "OTHER" : value.trim().toUpperCase().replaceAll("[^A-Z0-9_]", "_");
        if (normalized.isBlank()) normalized = "OTHER";
        return normalized.substring(0, Math.min(normalized.length(), 64));
    }
    private static String extension(String name) {
        if (name == null || !name.contains(".")) return "";
        String extension = name.substring(name.lastIndexOf('.')).toLowerCase();
        return extension.matches("\\.[a-z0-9]{1,8}") ? extension : "";
    }
    private static String sha256(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    public record DocumentView(UUID documentId, UUID applicationId, String category, String mediaType,
                               long sizeBytes, String scanStatus, boolean restricted, String reviewStatus,
                               String reviewReason, long version, java.time.Instant createdAt) {}
}
