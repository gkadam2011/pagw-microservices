package com.anthem.pagw.core.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Service for S3 operations - upload, download, delete objects.
 * All uploads use server-side encryption (KMS or AES256) for PHI compliance.
 */
@Slf4j
@Service
public class S3Service {

    private final S3Client s3Client;
    private final boolean kmsEnabled;
    private final String kmsKeyId;

    public S3Service(
            S3Client s3Client,
            @Value("${pagw.encryption.kms-enabled:false}") boolean kmsEnabled,
            @Value("${pagw.encryption.kms-key-id:}") String kmsKeyId) {
        this.s3Client = s3Client;
        this.kmsEnabled = kmsEnabled;
        this.kmsKeyId = kmsKeyId;
        
        if (kmsEnabled && (kmsKeyId == null || kmsKeyId.isBlank())) {
            log.warn("KMS encryption enabled but no key ID provided - falling back to AES256");
        }
        log.info("S3Service initialized: kmsEnabled={}, kmsKeyId={}", kmsEnabled, 
                kmsKeyId != null && !kmsKeyId.isBlank() ? kmsKeyId.substring(0, Math.min(8, kmsKeyId.length())) + "..." : "none");
    }

    public void uploadString(String bucket, String key, String content, String contentType) {
        try {
            PutObjectRequest.Builder requestBuilder = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType);
            
            // Apply encryption
            applyEncryption(requestBuilder);

            s3Client.putObject(requestBuilder.build(), RequestBody.fromString(content, StandardCharsets.UTF_8));
            log.debug("Uploaded string to s3://{}/{} (encrypted={})", bucket, key, getEncryptionType());
        } catch (Exception e) {
            log.error("Failed to upload string to s3://{}/{}", bucket, key, e);
            throw new RuntimeException("Failed to upload to S3", e);
        }
    }

    public void uploadBytes(String bucket, String key, byte[] content, String contentType) {
        try {
            PutObjectRequest.Builder requestBuilder = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType);
            
            // Apply encryption
            applyEncryption(requestBuilder);

            s3Client.putObject(requestBuilder.build(), RequestBody.fromBytes(content));
            log.debug("Uploaded {} bytes to s3://{}/{} (encrypted={})", content.length, bucket, key, getEncryptionType());
        } catch (Exception e) {
            log.error("Failed to upload bytes to s3://{}/{}", bucket, key, e);
            throw new RuntimeException("Failed to upload to S3", e);
        }
    }

    public void uploadStream(String bucket, String key, InputStream inputStream, 
                             long contentLength, String contentType) {
        try {
            PutObjectRequest.Builder requestBuilder = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType)
                    .contentLength(contentLength);
            
            // Apply encryption
            applyEncryption(requestBuilder);

            s3Client.putObject(requestBuilder.build(), RequestBody.fromInputStream(inputStream, contentLength));
            log.debug("Uploaded stream to s3://{}/{} (encrypted={})", bucket, key, getEncryptionType());
        } catch (Exception e) {
            log.error("Failed to upload stream to s3://{}/{}", bucket, key, e);
            throw new RuntimeException("Failed to upload to S3", e);
        }
    }
    
    /**
     * Apply server-side encryption to the request.
     * Uses KMS if enabled and key ID provided, otherwise falls back to AES256.
     */
    private void applyEncryption(PutObjectRequest.Builder requestBuilder) {
        if (kmsEnabled && kmsKeyId != null && !kmsKeyId.isBlank()) {
            requestBuilder
                    .serverSideEncryption(ServerSideEncryption.AWS_KMS)
                    .ssekmsKeyId(kmsKeyId);
        } else {
            // Default to AES256 encryption (bucket-level encryption will apply)
            requestBuilder.serverSideEncryption(ServerSideEncryption.AES256);
        }
    }
    
    private String getEncryptionType() {
        if (kmsEnabled && kmsKeyId != null && !kmsKeyId.isBlank()) {
            return "KMS";
        }
        return "AES256";
    }

    public String downloadString(String bucket, String key) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();

            return s3Client.getObjectAsBytes(request).asUtf8String();
        } catch (Exception e) {
            log.error("Failed to download string from s3://{}/{}", bucket, key, e);
            throw new RuntimeException("Failed to download from S3", e);
        }
    }

    public byte[] downloadBytes(String bucket, String key) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();

            return s3Client.getObjectAsBytes(request).asByteArray();
        } catch (Exception e) {
            log.error("Failed to download bytes from s3://{}/{}", bucket, key, e);
            throw new RuntimeException("Failed to download from S3", e);
        }
    }

    public boolean exists(String bucket, String key) {
        try {
            HeadObjectRequest request = HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();

            s3Client.headObject(request);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            log.error("Failed to check existence of s3://{}/{}", bucket, key, e);
            throw new RuntimeException("Failed to check S3 object existence", e);
        }
    }

    public void delete(String bucket, String key) {
        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();

            s3Client.deleteObject(request);
            log.debug("Deleted s3://{}/{}", bucket, key);
        } catch (Exception e) {
            log.error("Failed to delete s3://{}/{}", bucket, key, e);
            throw new RuntimeException("Failed to delete from S3", e);
        }
    }

    public String generateKey(String pagwId, String fileType, String extension) {
        return String.format("%s/%s/%s.%s", 
                pagwId.substring(0, 2), 
                pagwId, 
                fileType, 
                extension);
    }

    /**
     * Backwards compatibility alias for uploadString with default content type.
     */
    public void putObject(String bucket, String key, String content) {
        uploadString(bucket, key, content, "application/json");
    }

    /**
     * Backwards compatibility alias for downloadString.
     */
    public String getObject(String bucket, String key) {
        return downloadString(bucket, key);
    }
    
    // ===== FHIR Parsed Data Helpers =====
    
    /**
     * Store parsed FHIR data in S3 and return the path.
     * Path format: parsed-data/{tenant}/{pagwId}-parsed.json
     */
    public String putParsedData(String bucket, String tenant, String pagwId, String parsedDataJson) {
        String key = String.format("parsed-data/%s/%s-parsed.json", tenant, pagwId);
        uploadString(bucket, key, parsedDataJson, "application/json");
        return key;
    }
    
    /**
     * Retrieve parsed FHIR data from S3.
     */
    public String getParsedData(String bucket, String s3Path) {
        return downloadString(bucket, s3Path);
    }
}

