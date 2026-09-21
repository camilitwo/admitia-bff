package cl.mtn.admitiabff.prekinder.api;

import cl.mtn.admitiabff.prekinder.service.PrekinderDocumentService;
import cl.mtn.admitiabff.prekinder.service.PrekinderDomainException;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/prekinder")
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderDocumentController {
    private final PrekinderDocumentService documents;
    public PrekinderDocumentController(PrekinderDocumentService documents) { this.documents = documents; }

    @PostMapping("/applications/{applicationId}/documents")
    public Map<String, Object> upload(@PathVariable UUID applicationId, @RequestParam String category,
        @RequestParam("file") MultipartFile file) throws IOException {
        try {
            return Map.of("success", true, "data", documents.upload(applicationId, category, file));
        } catch (IOException exception) {
            throw new PrekinderDomainException("DOCUMENT_UPLOAD_FAILED",
                "No fue posible almacenar el documento; inténtalo nuevamente", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    @GetMapping("/applications/{applicationId}/documents")
    public Map<String, Object> list(@PathVariable UUID applicationId) {
        return Map.of("success", true, "data", documents.list(applicationId));
    }

    @GetMapping("/documents/{documentId}")
    public ResponseEntity<ByteArrayResource> download(@PathVariable UUID documentId) throws IOException {
        return documents.download(documentId);
    }

    @PutMapping("/documents/{documentId}/review")
    public Map<String, Object> review(@PathVariable UUID documentId, @RequestBody ReviewDocument request) {
        return Map.of("success", true, "data",
            documents.review(documentId, request.decision(), request.reason(), request.expectedVersion()));
    }

    public record ReviewDocument(String decision, String reason, long expectedVersion) {}
}
