package com.anthem.pagw.orchestrator.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Diagnostic endpoints for troubleshooting AWS connectivity.
 * Only enabled in dev environment for security.
 */
@RestController
@RequestMapping("/actuator/diagnostic")
@RequiredArgsConstructor
@Slf4j
@Profile("dev")
public class DiagnosticController {

    @Value("${pagw.aws.region:us-east-2}")
    private String awsRegion;

    @Value("${AWS_WEB_IDENTITY_TOKEN_FILE:not-set}")
    private String webIdentityTokenFile;

    @Value("${AWS_ROLE_ARN:not-set}")
    private String roleArn;

    /**
     * Check AWS credentials and identity.
     * This helps verify IRSA is working correctly.
     */
    @GetMapping("/aws-identity")
    public ResponseEntity<Map<String, Object>> checkAwsIdentity() {
        Map<String, Object> result = new LinkedHashMap<>();
        
        // Environment info
        result.put("configuredRegion", awsRegion);
        result.put("webIdentityTokenFile", webIdentityTokenFile);
        result.put("roleArn", roleArn);
        
        // Check environment variables
        result.put("AWS_REGION_ENV", System.getenv("AWS_REGION"));
        result.put("AWS_DEFAULT_REGION_ENV", System.getenv("AWS_DEFAULT_REGION"));
        result.put("AWS_WEB_IDENTITY_TOKEN_FILE_ENV", System.getenv("AWS_WEB_IDENTITY_TOKEN_FILE"));
        result.put("AWS_ROLE_ARN_ENV", System.getenv("AWS_ROLE_ARN"));
        
        // Try to get caller identity
        try (StsClient stsClient = StsClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build()) {
            
            GetCallerIdentityResponse identity = stsClient.getCallerIdentity();
            result.put("status", "SUCCESS");
            result.put("account", identity.account());
            result.put("arn", identity.arn());
            result.put("userId", identity.userId());
            
            log.info("AWS identity check successful: {}", identity.arn());
            return ResponseEntity.ok(result);
            
        } catch (SdkException e) {
            result.put("status", "FAILED");
            result.put("error", e.getMessage());
            result.put("errorType", e.getClass().getSimpleName());
            
            log.error("AWS identity check failed: {}", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }
}
