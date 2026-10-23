package com.js.motos.clientApp.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.js.motos.clientApp.common.ApiException;
import com.js.motos.clientApp.common.ErrorCode;
import com.js.motos.clientApp.config.AppProperties;

/**
 * Cloudinary-backed receipt storage, driven straight off their signed upload REST
 * API rather than the SDK — one fewer dependency to keep in step with Spring Boot.
 *
 * <p>Signing rule: SHA-1 over the alphabetically sorted, {@code &}-joined
 * parameters (excluding {@code file}, {@code api_key} and {@code resource_type})
 * with the API secret appended.
 */
@Service
@ConditionalOnProperty(name = "app.cloudinary.enabled", havingValue = "true")
public class CloudinaryStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(CloudinaryStorage.class);

    private final AppProperties.Cloudinary config;
    private final RestClient restClient;

    public CloudinaryStorage(AppProperties properties, RestClient.Builder builder) {
        this.config = properties.cloudinary();

        if (config.cloudName().isBlank() || config.apiKey().isBlank() || config.apiSecret().isBlank()) {
            throw new IllegalStateException(
                    "Cloudinary is enabled but cloud-name / api-key / api-secret are not all set.");
        }
        this.restClient = builder
                .baseUrl("https://api.cloudinary.com/v1_1/" + config.cloudName())
                .build();
    }

    @Override
    public StoredFile upload(MultipartFile file, String folderHint) {
        ImageValidator.validate(file);

        long timestamp = System.currentTimeMillis() / 1000;
        String folder = config.folder() + "/" + folderHint;
        String publicId = folderHint + "-" + UUID.randomUUID();

        // Only these params take part in the signature.
        Map<String, String> signed = new TreeMap<>();
        signed.put("folder", folder);
        signed.put("public_id", publicId);
        signed.put("timestamp", String.valueOf(timestamp));

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        signed.forEach(form::add);
        form.add("api_key", config.apiKey());
        form.add("signature", sign(signed));
        form.add("file", asResource(file));

        try {
            JsonNode response = restClient.post()
                    .uri("/auto/upload")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null || !response.hasNonNull("secure_url")) {
                throw new ApiException(ErrorCode.UPLOAD_FAILED, "Upload provider returned no URL.");
            }
            return new StoredFile(
                    response.get("secure_url").asText(),
                    response.get("public_id").asText());

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Cloudinary upload failed", e);
            throw new ApiException(ErrorCode.UPLOAD_FAILED,
                    "Could not upload the receipt. Please try again.");
        }
    }

    @Override
    public void delete(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            return;
        }
        try {
            long timestamp = System.currentTimeMillis() / 1000;
            Map<String, String> signed = new TreeMap<>();
            signed.put("public_id", publicId);
            signed.put("timestamp", String.valueOf(timestamp));

            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            signed.forEach(form::add);
            form.add("api_key", config.apiKey());
            form.add("signature", sign(signed));

            restClient.post()
                    .uri("/image/destroy")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // A stale file costs storage, not correctness — never fail the caller.
            log.warn("Could not delete Cloudinary object {}", publicId, e);
        }
    }

    private String sign(Map<String, String> sortedParams) {
        StringBuilder toSign = new StringBuilder();
        sortedParams.forEach((key, value) -> {
            if (toSign.length() > 0) {
                toSign.append('&');
            }
            toSign.append(key).append('=').append(value);
        });
        toSign.append(config.apiSecret());

        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(sha1.digest(toSign.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    /** Wraps the upload so the multipart part carries the original filename. */
    private static ByteArrayResource asResource(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            String filename = "receipt" + ImageValidator.safeExtension(file);
            return new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };
        } catch (Exception e) {
            throw new ApiException(ErrorCode.UPLOAD_FAILED, "Could not read the uploaded file.");
        }
    }
}