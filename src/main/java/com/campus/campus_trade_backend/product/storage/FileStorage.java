package com.campus.campus_trade_backend.product.storage;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface FileStorage {
    StoredFile save(MultipartFile file, String extension);
    Resource load(String storageKey);
    void delete(String storageKey);

    record StoredFile(String storageKey, long size) {
    }
}
