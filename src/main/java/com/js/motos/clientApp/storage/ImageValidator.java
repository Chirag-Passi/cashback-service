package com.js.motos.clientApp.storage;

import java.util.Set;

import org.springframework.web.multipart.MultipartFile;

import com.js.motos.clientApp.common.ApiException;
import com.js.motos.clientApp.common.ErrorCode;

/** Shared upload checks applied before any bytes reach a storage provider. */
final class ImageValidator {

    private static final Set<String> ALLOWED_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp", "image/heic", "application/pdf");

    private static final long MAX_BYTES = 8L * 1024 * 1024;

    private ImageValidator() {
    }

    static void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.RECEIPT_REQUIRED, "A receipt file is required.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Receipt must be 8 MB or smaller.");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Receipt must be a JPEG, PNG, WEBP, HEIC image or a PDF.");
        }
    }

    /** Strips any path components a client may have put in the filename. */
    static String safeExtension(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        String ext = name.substring(dot + 1).toLowerCase();
        return ext.matches("[a-z0-9]{1,5}") ? "." + ext : "";
    }
}