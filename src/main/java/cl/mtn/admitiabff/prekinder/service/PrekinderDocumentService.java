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

@Service
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderDocumentService {
    private final NamedParameterJdbcTemplate jdbc;
    private final PrekinderAccessService access;
    private final VercelBlobService blobs;
    private final Path localRoot;

    public PrekinderDocumentService(@Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc,
        PrekinderAccessService access, VercelBlobService blobs, @Value("${app.uploads-dir:uploads}") String uploadsDir) {
        this.jdbc = jdbc; this.access = access; this.blobs = blobs;
        this.localRoot = Path.of(uploadsDir).toAbsolutePath().resolve("prekinder");
    }

    public DocumentView upload(UUID applicationId, String category, MultipartFile file) throws IOException {
        PrekinderActor actor = access.requireActor();
        assertAccess(applicationId, actor);
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
            storageKey = blobs.upload(bytes, objectKey, mediaType).url;
        } else {
            Files.createDirectories(localRoot.resolve(applicationId.toString()));
            Path target = localRoot.resolve(applicationId.toString()).resolve(id + extension);
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            storageKey = target.toString();
        }
        jdbc.update("""
            INSERT INTO document_metadata(document_id, application_id, category, storage_key, media_type,
                size_bytes, sha256, scan_status, restricted, uploaded_by)
            VALUES (:id, :applicationId, :category, :storageKey, :mediaType, :size, :sha256, 'PENDING', false, :actorId)
            """, Map.of("id", id, "applicationId", applicationId, "category", normalizeCategory(category),
            "storageKey", storageKey, "mediaType", mediaType, "size", file.getSize(),
            "sha256", sha256(bytes), "actorId", actor.id()));
        return document(id);
    }

    public List<DocumentView> list(UUID applicationId) {
        PrekinderActor actor = access.requireActor();
        assertAccess(applicationId, actor);
        return jdbc.query("""
            SELECT document_id, application_id, category, media_type, size_bytes, scan_status, restricted, created_at
              FROM document_metadata WHERE application_id = :id ORDER BY created_at DESC
            """, Map.of("id", applicationId), (rs, row) -> new DocumentView(rs.getObject("document_id", UUID.class),
                rs.getObject("application_id", UUID.class), rs.getString("category"), rs.getString("media_type"),
                rs.getLong("size_bytes"), rs.getString("scan_status"), rs.getBoolean("restricted"),
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
        if (List.of("ADMIN", "COORDINATOR", "CYCLE_DIRECTOR").contains(actor.role())) return;
        Long count = jdbc.queryForObject("""
            SELECT count(*) FROM applications a JOIN applicants ap ON ap.applicant_id = a.applicant_id
              JOIN families f ON f.family_id = ap.family_id
             WHERE a.application_id = :applicationId AND f.external_reference = :actorId
            """, Map.of("applicationId", applicationId, "actorId", actor.id().toString()), Long.class);
        if (count == null || count == 0) throw PrekinderDomainException.forbidden("NOT_ASSIGNED", "Documento no autorizado");
    }

    private DocumentView document(UUID id) {
        return jdbc.queryForObject("""
            SELECT document_id, application_id, category, media_type, size_bytes, scan_status, restricted, created_at
              FROM document_metadata WHERE document_id = :id
            """, Map.of("id", id), (rs, row) -> new DocumentView(id, rs.getObject("application_id", UUID.class),
                rs.getString("category"), rs.getString("media_type"), rs.getLong("size_bytes"),
                rs.getString("scan_status"), rs.getBoolean("restricted"), rs.getTimestamp("created_at").toInstant()));
    }

    private static String normalizeCategory(String value) {
        String normalized = value == null ? "OTHER" : value.trim().toUpperCase().replaceAll("[^A-Z0-9_]", "_");
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
                               long sizeBytes, String scanStatus, boolean restricted, java.time.Instant createdAt) {}
}
