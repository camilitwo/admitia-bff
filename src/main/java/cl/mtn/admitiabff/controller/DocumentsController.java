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

@RestController
@RequestMapping("/api/documents")
public class DocumentsController {
    private final DocumentService documentService;

    public DocumentsController(DocumentService documentService) { this.documentService = documentService; }

    @PostMapping public Map<String, Object> upload(@RequestParam("files") List<MultipartFile> files, @RequestParam Map<String, String> params) throws IOException { return documentService.upload(files, new HashMap<>(params)); }
    @GetMapping("/application/{applicationId}") public Map<String, Object> byApplication(@PathVariable Long applicationId) { return documentService.byApplication(applicationId); }
    @GetMapping("/{id}/download") public ResponseEntity<ByteArrayResource> download(@PathVariable Long id) throws IOException { return documentService.download(id, false); }
    @GetMapping("/view/{id}") public ResponseEntity<ByteArrayResource> view(@PathVariable Long id) throws IOException { return documentService.download(id, true); }
    @PutMapping("/{id}") public Map<String, Object> replace(@PathVariable Long id, @RequestParam("file") MultipartFile file) throws IOException { return documentService.replace(id, file); }
    @PutMapping("/{id}/approval") public Map<String, Object> approval(@PathVariable Long id, @RequestBody Map<String, Object> payload) { return documentService.approval(id, payload); }
    @DeleteMapping("/{id}") public Map<String, Object> delete(@PathVariable Long id) throws IOException { return documentService.delete(id); }
}
