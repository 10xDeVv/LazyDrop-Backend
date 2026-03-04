package com.lazydrop.modules.storage.service;

import com.lazydrop.config.SpacesProperties;
import com.lazydrop.modules.session.file.dto.SignedUploadResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.UUID;

@Service
@Slf4j
public class S3StorageService implements StorageService {

    private final S3Client s3Client;
    private final S3Presigner originPresigner;
    private final S3Presigner cdnPresigner;
    private final SpacesProperties spaces;

    public S3StorageService(
            S3Client s3Client,
            @Qualifier("originPresigner") S3Presigner originPresigner,
            @Qualifier("cdnPresigner") S3Presigner cdnPresigner,
            SpacesProperties spaces
    ) {
        this.s3Client = s3Client;
        this.originPresigner = originPresigner;
        this.cdnPresigner = cdnPresigner;
        this.spaces = spaces;
    }

    @Override
    public SignedUploadResponse createSignedUploadUrl(String folderPrefix, String fileName, String contentType, int expiresInSec) {
        String objectPath = String.format("%s/%s/%s", folderPrefix, UUID.randomUUID().toString().substring(0, 8), fileName);

        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(spaces.getBucketName())
                .key(objectPath)
                .contentType(contentType)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(expiresInSec))
                .putObjectRequest(putRequest)
                .build();

        // Uploads always go to origin (CDN doesn't accept writes)
        String signedUrl = originPresigner.presignPutObject(presignRequest).url().toString();

        log.info("Created signed upload URL for: {} (expires in {} sec)", fileName, expiresInSec);

        return SignedUploadResponse.builder()
                .signedUrl(signedUrl)
                .objectPath(objectPath)
                .token(null)
                .expiresIn(expiresInSec)
                .build();
    }

    @Override
    public String createSignedDownloadUrl(String objectPath, int expiresInSec) {
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(spaces.getBucketName())
                .key(objectPath)
                .responseContentDisposition("attachment; filename=\"" + fileNameFrom(objectPath) + "\"")
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(expiresInSec))
                .getObjectRequest(getRequest)
                .build();

        // Downloads go through CDN for edge caching
        String signedUrl = cdnPresigner.presignGetObject(presignRequest).url().toString();

        log.info("Created signed CDN download URL (expires in {} sec)", expiresInSec);
        return signedUrl;
    }

    @Override
    public void deleteFile(String objectPath) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(spaces.getBucketName())
                    .key(objectPath)
                    .build());
            log.info("Deleted: {}", objectPath);
        } catch (Exception e) {
            log.error("Error deleting file {}", objectPath, e);
            throw new RuntimeException("Failed to delete file", e);
        }
    }

    private static String fileNameFrom(String objectPath) {
        return objectPath.substring(objectPath.lastIndexOf('/') + 1);
    }
}
