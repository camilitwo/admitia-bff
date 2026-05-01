package cl.mtn.admitiabff.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Minimal Vercel Blob client (REST API) — equivalent to @vercel/blob's put/del/head.
 * Activated when app.blob.token is set; otherwise document storage falls back to local disk.
 */
@Service
public class VercelBlobService {
    private static final Logger log = LoggerFactory.getLogger(VercelBlobService.class);
    private static final String BLOB_API = "https://blob.vercel-storage.com";

    private final String token;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public VercelBlobService(@Value("${app.blob.token:}") String token) {
        this.token = token == null ? "" : token.trim();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        if (this.token.isBlank()) {
            log.warn("[VercelBlob] BLOB_READ_WRITE_TOKEN not configured — uploads will use local disk");
        } else {
            log.info("[VercelBlob] Configured");
        }
    }

    public boolean isEnabled() {
        return !token.isBlank();
    }

    public BlobUploadResult upload(byte[] data, String pathname, String contentType) throws IOException {
        if (!isEnabled()) {
            throw new IllegalStateException("Vercel Blob not configured");
        }
        String encodedPath = URLEncoder.encode(pathname, StandardCharsets.UTF_8).replace("+", "%20");
        // Omit access= param/header: the store's configured access mode (private/public)
        // is authoritative. Sending "public" to a private store (or vice-versa) errors out.
        URI uri = URI.create(BLOB_API + "/" + encodedPath);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token)
                .header("x-content-type", contentType == null ? "application/octet-stream" : contentType)
                .header("x-api-version", "7")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(data))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("Vercel Blob PUT failed: HTTP " + resp.statusCode() + " body=" + resp.body());
            }
            JsonNode json = mapper.readTree(resp.body());
            BlobUploadResult result = new BlobUploadResult();
            result.url = json.path("url").asText();
            result.pathname = json.path("pathname").asText(pathname);
            result.contentType = json.path("contentType").asText(contentType);
            result.size = json.path("size").asLong(data.length);
            log.info("[VercelBlob] Uploaded {} ({} bytes) -> {}", pathname, result.size, result.url);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Vercel Blob upload interrupted", e);
        }
    }

    public void delete(String url) throws IOException {
        if (!isEnabled() || url == null || url.isBlank()) return;
        URI uri = URI.create(BLOB_API + "/delete");
        String body = "{\"urls\":[\"" + url.replace("\"", "\\\"") + "\"]}";
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("x-api-version", "7")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[VercelBlob] DELETE failed: HTTP {} body={}", resp.statusCode(), resp.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Vercel Blob delete interrupted", e);
        }
    }

    public byte[] download(String url) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET();
        // Private blobs require Bearer token; public blobs accept either.
        if (!token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpRequest req = builder.build();
        try {
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("Vercel Blob GET failed: HTTP " + resp.statusCode());
            }
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Vercel Blob download interrupted", e);
        }
    }

    public static class BlobUploadResult {
        public String url;
        public String pathname;
        public String contentType;
        public long size;
    }
}
