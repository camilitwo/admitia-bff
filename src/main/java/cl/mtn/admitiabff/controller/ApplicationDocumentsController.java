package cl.mtn.admitiabff.controller;

import cl.mtn.admitiabff.service.DocumentService;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Alias controller exposing document operations under /api/applications/documents,
 * matching the legacy backend (sistema-admision-mtn-backend) and the frontend contract.
 * Delegates to the same DocumentService used by /api/documents.
 */
@RestController
@RequestMapping("/api/applications/documents")
public class ApplicationDocumentsController {
    private final DocumentService documentService;

    public ApplicationDocumentsController(DocumentService documentService) { this.documentService = documentService; }

    @PostMapping
    public Map<String, Object> upload(@RequestParam("files") List<MultipartFile> files,
                                      @RequestParam Map<String, String> params) throws IOException {
        return documentService.upload(files, new HashMap<>(params));
    }

    @GetMapping("/view/{id}")
    public ResponseEntity<ByteArrayResource> view(@PathVariable Long id) throws IOException {
        return documentService.download(id, true);
    }

    @PutMapping("/{id}/approval")
    public Map<String, Object> approval(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        return documentService.approval(id, payload);
    }

    @PutMapping("/{id}")
    public Map<String, Object> replace(@PathVariable Long id, @RequestParam("file") MultipartFile file) throws IOException {
        return documentService.replace(id, file);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) throws IOException {
        return documentService.delete(id);
    }
}
