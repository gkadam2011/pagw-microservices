package com.anthem.pagw.core.service;

import com.anthem.pagw.core.PagwProperties;
import com.anthem.pagw.core.exception.PagwException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.*;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * PHI Encryption Service using AWS KMS envelope encryption.
 * 
 * Strategy:
 * 1. Generate a Data Encryption Key (DEK) from KMS
 * 2. Use DEK to encrypt PHI data locally (AES-256-GCM)
 * 3. Store encrypted DEK alongside encrypted data
 * 4. For decryption, use KMS to decrypt the DEK, then decrypt data locally
 * 
 * This provides:
 * - Field-level encryption for sensitive PHI
 * - Key rotation via KMS
 * - Audit trail via CloudTrail
 * - Performance (local encryption with DEK)
 * 
 * Note: This service is only available when KMS is enabled.
 * In local/dev environments, encryption can be disabled via configuration.
 */
@Service
@ConditionalOnBean(KmsClient.class)
public class PhiEncryptionService {
    
    private static final Logger log = LoggerFactory.getLogger(PhiEncryptionService.class);
    
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    
    private final KmsClient kmsClient;
    private final PagwProperties properties;
    
    public PhiEncryptionService(KmsClient kmsClient, PagwProperties properties) {
        this.kmsClient = kmsClient;
        this.properties = properties;
    }
    
    /**
     * Encrypt PHI data using envelope encryption.
     * 
     * @param plaintext The PHI data to encrypt
     * @param context Encryption context for additional security (e.g., pagwId)
     * @return EncryptedData containing encrypted data and encrypted DEK
     */
    public EncryptedData encrypt(String plaintext, Map<String, String> context) {
        try {
            // Generate Data Encryption Key from KMS
            GenerateDataKeyRequest dataKeyRequest = GenerateDataKeyRequest.builder()
                    .keyId(properties.getKmsPhiKeyAlias())
                    .keySpec(DataKeySpec.AES_256)
                    .encryptionContext(context)
                    .build();
            
            GenerateDataKeyResponse dataKeyResponse = kmsClient.generateDataKey(dataKeyRequest);
            
            byte[] plaintextKey = dataKeyResponse.plaintext().asByteArray();
            byte[] encryptedKey = dataKeyResponse.ciphertextBlob().asByteArray();
            
            // Generate IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);
            
            // Encrypt data locally using the plaintext DEK
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(plaintextKey, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
            
            byte[] encryptedData = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            
            // Clear plaintext key from memory
            java.util.Arrays.fill(plaintextKey, (byte) 0);
            
            // Combine IV + encrypted data
            byte[] combined = new byte[iv.length + encryptedData.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encryptedData, 0, combined, iv.length, encryptedData.length);
            
            log.debug("PHI data encrypted successfully");
            
            return new EncryptedData(
                    Base64.getEncoder().encodeToString(combined),
                    Base64.getEncoder().encodeToString(encryptedKey)
            );
            
        } catch (Exception e) {
            log.error("Failed to encrypt PHI data", e);
            throw new PagwException("PHI_ENCRYPTION_FAILED", "Failed to encrypt PHI data", e);
        }
    }
    
    /**
     * Decrypt PHI data using envelope encryption.
     * 
     * @param encryptedData The encrypted data object
     * @param context Encryption context (must match encryption context)
     * @return Decrypted plaintext
     */
    public String decrypt(EncryptedData encryptedData, Map<String, String> context) {
        try {
            // Decrypt the DEK using KMS
            DecryptRequest decryptRequest = DecryptRequest.builder()
                    .ciphertextBlob(SdkBytes.fromByteArray(
                            Base64.getDecoder().decode(encryptedData.getEncryptedKey())))
                    .keyId(properties.getKmsPhiKeyAlias())
                    .encryptionContext(context)
                    .build();
            
            DecryptResponse decryptResponse = kmsClient.decrypt(decryptRequest);
            byte[] plaintextKey = decryptResponse.plaintext().asByteArray();
            
            // Decode the combined data (IV + encrypted data)
            byte[] combined = Base64.getDecoder().decode(encryptedData.getCiphertext());
            
            // Extract IV and encrypted data
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            System.arraycopy(combined, iv.length, ciphertext, 0, ciphertext.length);
            
            // Decrypt data locally
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(plaintextKey, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
            
            byte[] decryptedData = cipher.doFinal(ciphertext);
            
            // Clear plaintext key from memory
            java.util.Arrays.fill(plaintextKey, (byte) 0);
            
            log.debug("PHI data decrypted successfully");
            
            return new String(decryptedData, StandardCharsets.UTF_8);
            
        } catch (Exception e) {
            log.error("Failed to decrypt PHI data", e);
            throw new PagwException("PHI_DECRYPTION_FAILED", "Failed to decrypt PHI data", e);
        }
    }
    
    /**
     * Encrypt specific PHI fields in a JSON object.
     * This method encrypts individual fields while preserving the JSON structure.
     * 
     * @param jsonData Original JSON data
     * @param phiFields List of field paths to encrypt (e.g., "patient.name", "patient.ssn")
     * @param context Encryption context
     * @return JSON with specified PHI fields encrypted
     */
    public String encryptPhiFields(String jsonData, java.util.List<String> phiFields, Map<String, String> context) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode rootNode = mapper.readTree(jsonData);
            
            if (rootNode.isObject()) {
                com.fasterxml.jackson.databind.node.ObjectNode objectNode = (com.fasterxml.jackson.databind.node.ObjectNode) rootNode;
                
                for (String fieldPath : phiFields) {
                    encryptFieldAtPath(objectNode, fieldPath.split("\\."), 0, context);
                }
            }
            
            return mapper.writeValueAsString(rootNode);
            
        } catch (Exception e) {
            log.error("Failed to encrypt PHI fields", e);
            throw new PagwException("PHI_FIELD_ENCRYPTION_FAILED", "Failed to encrypt PHI fields", e);
        }
    }
    
    private void encryptFieldAtPath(com.fasterxml.jackson.databind.node.ObjectNode node, 
                                     String[] path, int index, Map<String, String> context) {
        if (index >= path.length) return;
        
        String field = path[index];
        com.fasterxml.jackson.databind.JsonNode fieldNode = node.get(field);
        
        if (fieldNode == null) return;
        
        if (index == path.length - 1) {
            // Encrypt this field
            if (fieldNode.isTextual()) {
                EncryptedData encrypted = encrypt(fieldNode.asText(), context);
                com.fasterxml.jackson.databind.node.ObjectNode encryptedNode = node.putObject(field + "_encrypted");
                encryptedNode.put("ciphertext", encrypted.getCiphertext());
                encryptedNode.put("encryptedKey", encrypted.getEncryptedKey());
                node.remove(field);
            }
        } else if (fieldNode.isObject()) {
            encryptFieldAtPath((com.fasterxml.jackson.databind.node.ObjectNode) fieldNode, path, index + 1, context);
        }
    }
    
    /**
     * Container for encrypted data with the encrypted DEK.
     */
    public static class EncryptedData {
        private final String ciphertext;
        private final String encryptedKey;
        
        public EncryptedData(String ciphertext, String encryptedKey) {
            this.ciphertext = ciphertext;
            this.encryptedKey = encryptedKey;
        }
        
        public String getCiphertext() {
            return ciphertext;
        }
        
        public String getEncryptedKey() {
            return encryptedKey;
        }
        
        @Override
        public String toString() {
            return "EncryptedData{ciphertext=[REDACTED], encryptedKey=[REDACTED]}";
        }
    }
}
