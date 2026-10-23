package com.js.motos.clientApp.storage;

import org.springframework.web.multipart.MultipartFile;

/** Where payment receipt screenshots live. */
public interface FileStorage {

    /**
     * Stores an image and returns its public URL plus a provider handle.
     *
     * @param folderHint logical folder, e.g. {@code receipts}
     */
    StoredFile upload(MultipartFile file, String folderHint);

    /** Best-effort delete; never throws for a missing object. */
    void delete(String publicId);

    /** @param publicId provider-side handle used for later deletion */
    record StoredFile(String url, String publicId) {
    }
}