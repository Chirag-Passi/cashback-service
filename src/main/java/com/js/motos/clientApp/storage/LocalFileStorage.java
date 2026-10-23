package com.js.motos.clientApp.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.js.motos.clientApp.common.ApiException;
import com.js.motos.clientApp.common.ErrorCode;
import com.js.motos.clientApp.config.AppProperties;

/**
 * Development fallback that writes receipts to local disk.
 *
 * <p>Not suitable for production on ephemeral hosts (Render, Railway, Fly free
 * tiers) — the filesystem is wiped on every deploy. Enable Cloudinary there.
 */
@Service
@ConditionalOnProperty(name = "app.cloudinary.enabled", havingValue = "false", matchIfMissing = true)
public class LocalFileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);

    private final Path root;
    private final String baseUrl;

    public LocalFileStorage(AppProperties properties) {
        this.root = Paths.get(properties.storage().localDir()).toAbsolutePath().normalize();
        this.baseUrl = properties.baseUrl();
        log.warn("Using local disk receipt storage at {} — files will not survive a redeploy.", root);
    }

    @Override
    public FileStorage.StoredFile upload(MultipartFile file, String folderHint) {
        ImageValidator.validate(file);

        String filename = folderHint + "-" + UUID.randomUUID() + ImageValidator.safeExtension(file);
        try {
            Path folder = root.resolve(folderHint).normalize();
            // Guard against a folderHint that tries to escape the storage root.
            if (!folder.startsWith(root)) {
                throw new ApiException(ErrorCode.UPLOAD_FAILED, "Invalid storage location.");
            }
            Files.createDirectories(folder);

            Path target = folder.resolve(filename);
            try (var input = file.getInputStream()) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }

            String url = baseUrl + "/files/" + folderHint + "/" + filename;
            return new FileStorage.StoredFile(url, folderHint + "/" + filename);

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Local receipt write failed", e);
            throw new ApiException(ErrorCode.UPLOAD_FAILED, "Could not store the receipt.");
        }
    }

    @Override
    public void delete(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            return;
        }
        try {
            Path target = root.resolve(publicId).normalize();
            if (target.startsWith(root)) {
                Files.deleteIfExists(target);
            }
        } catch (Exception e) {
            log.warn("Could not delete local file {}", publicId, e);
        }
    }
}