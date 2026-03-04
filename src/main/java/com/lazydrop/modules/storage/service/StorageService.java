package com.lazydrop.modules.storage.service;

import com.lazydrop.modules.session.file.dto.SignedUploadResponse;

public interface StorageService {

    SignedUploadResponse createSignedUploadUrl(String folderPrefix, String fileName, String contentType, int expiresInSec);

    String createSignedDownloadUrl(String objectPath, int expiresInSec);

    void deleteFile(String objectPath);
}
