package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.common.DocumentApprovalStatus;
import cl.mtn.admitiabff.domain.document.DocumentEntity;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import cl.mtn.admitiabff.repository.DocumentRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@Transactional(readOnly = true)
public class DocumentService {
    private final DocumentRepository documentRepository;
    private final ApplicationRepository applicationRepository;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final Path uploadsDir;

    public DocumentService(DocumentRepository documentRepository, ApplicationRepository applicationRepository, UserRepository userRepository, AuthService authService, @Value("${app.uploads-dir}") String uploadsDir) {
        this.documentRepository = documentRepository;
        this.applicationRepository = applicationRepository;
        this.userRepository = userRepository;
        this.authService = authService;
        this.uploadsDir = Path.of(uploadsDir).toAbsolutePath();
    }

    @Transactional
    public Map<String, Object> upload(List<MultipartFile> files, Map<String, Object> metadata) throws IOException {
        Long applicationId = Long.parseLong(String.valueOf(metadata.get("applicationId")));
        Files.createDirectories(uploadsDir);
        var application = applicationRepository.findActiveById(applicationId).orElseThrow(() -> new IllegalArgumentException("Postulación no encontrada"));
        for (MultipartFile file : files) {
            DocumentEntity document = new DocumentEntity();
            document.setApplication(application);
            document.setDocumentType(String.valueOf(metadata.getOrDefault("documentType", "GENERAL")));
            document.setOriginalName(file.getOriginalFilename());
            document.setFileName(UUID.randomUUID() + "-" + file.getOriginalFilename());
            Path target = uploadsDir.resolve(document.getFileName());
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            document.setFilePath(target.toString());
            document.setFileSize(file.getSize());
            document.setContentType(file.getContentType());
            document.setRequired(Boolean.parseBoolean(String.valueOf(metadata.getOrDefault("isRequired", false))));
            document.setApprovalStatus(DocumentApprovalStatus.PENDING);
            document.setUploadDate(LocalDateTime.now());
            documentRepository.save(document);
        }
        return byApplication(applicationId);
    }

    public Map<String, Object> byApplication(Long applicationId) {
        List<Map<String, Object>> data = documentRepository.findByApplicationIdOrderByUploadDateDesc(applicationId).stream().map(this::toResponse).toList();
        return Map.of("success", true, "data", data, "count", data.size());
    }

    public ResponseEntity<ByteArrayResource> download(Long id, boolean inline) throws IOException {
        DocumentEntity document = documentRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Documento no encontrado"));
        byte[] bytes = Files.readAllBytes(Path.of(document.getFilePath()));
        MediaType mediaType = document.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(document.getContentType());
        return ResponseEntity.ok().contentType(mediaType).header("Content-Disposition", (inline ? "inline" : "attachment") + "; filename=\"" + document.getOriginalName() + "\"").body(new ByteArrayResource(bytes));
    }

    @Transactional
    public Map<String, Object> replace(Long id, MultipartFile file) throws IOException {
        DocumentEntity document = documentRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Documento no encontrado"));
        Files.createDirectories(uploadsDir);
        document.setFileName(UUID.randomUUID() + "-" + file.getOriginalFilename());
        Path target = uploadsDir.resolve(document.getFileName());
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        document.setFilePath(target.toString());
        document.setOriginalName(file.getOriginalFilename());
        document.setFileSize(file.getSize());
        document.setContentType(file.getContentType());
        document.setApprovalStatus(DocumentApprovalStatus.PENDING);
        document.setApprovedBy(null);
        document.setApprovalDate(null);
        document.setRejectionReason(null);
        documentRepository.save(document);
        return byApplication(document.getApplication().getId());
    }

    @Transactional
    public Map<String, Object> approval(Long id, Map<String, Object> payload) {
        DocumentEntity document = documentRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Documento no encontrado"));
        document.setApprovalStatus(DocumentApprovalStatus.valueOf(String.valueOf(payload.getOrDefault("approvalStatus", "PENDING"))));
        document.setRejectionReason(payload.get("rejectionReason") == null ? null : String.valueOf(payload.get("rejectionReason")));
        document.setApprovalDate(LocalDateTime.now());
        document.setApprovedBy(authService.requireAuthenticatedUser());
        documentRepository.save(document);
        return byApplication(document.getApplication().getId());
    }

    @Transactional
    public Map<String, Object> delete(Long id) throws IOException {
        DocumentEntity document = documentRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Documento no encontrado"));
        Files.deleteIfExists(Path.of(document.getFilePath()));
        Long applicationId = document.getApplication().getId();
        documentRepository.delete(document);
        return byApplication(applicationId);
    }

    private Map<String, Object> toResponse(DocumentEntity document) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", document.getId());
        response.put("documentType", document.getDocumentType());
        response.put("fileName", document.getFileName());
        response.put("originalName", document.getOriginalName());
        response.put("filePath", document.getFilePath());
        response.put("fileSize", document.getFileSize());
        response.put("contentType", document.getContentType());
        response.put("isRequired", document.isRequired());
        response.put("approvalStatus", document.getApprovalStatus().name());
        response.put("rejectionReason", document.getRejectionReason());
        response.put("approvedBy", document.getApprovedBy() == null ? null : document.getApprovedBy().getId());
        response.put("approvalDate", document.getApprovalDate());
        response.put("uploadDate", document.getUploadDate());
        response.put("updatedAt", document.getUpdatedAt());
        return response;
    }
}
