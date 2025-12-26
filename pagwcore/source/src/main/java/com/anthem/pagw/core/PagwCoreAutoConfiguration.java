package com.anthem.pagw.core;

import com.anthem.pagw.core.service.IdempotencyService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.kms.KmsClient;

/**
 * Auto-configuration for PAGW Core components.
 * Automatically configures AWS clients and core services.
 * 
 * AWS client beans use @ConditionalOnMissingBean to allow applications
 * to define their own clients (e.g., for LocalStack in local development).
 */
@AutoConfiguration
@ComponentScan(basePackages = "com.anthem.pagw.core")
@EnableConfigurationProperties(PagwProperties.class)
public class PagwCoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SqsClient.class)
    @ConditionalOnProperty(prefix = "pagw.aws.sqs", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SqsClient sqsClient(PagwProperties properties) {
        return SqsClient.builder()
                .region(Region.of(properties.getAws().getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(S3Client.class)
    @ConditionalOnProperty(prefix = "pagw.aws.s3", name = "enabled", havingValue = "true", matchIfMissing = true)
    public S3Client s3Client(PagwProperties properties) {
        return S3Client.builder()
                .region(Region.of(properties.getAws().getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(SecretsManagerClient.class)
    @ConditionalOnProperty(prefix = "pagw.aws.secrets", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SecretsManagerClient secretsManagerClient(PagwProperties properties) {
        return SecretsManagerClient.builder()
                .region(Region.of(properties.getAws().getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(DynamoDbClient.class)
    @ConditionalOnProperty(prefix = "pagw.aws.dynamodb", name = "enabled", havingValue = "true", matchIfMissing = false)
    public DynamoDbClient dynamoDbClient(PagwProperties properties) {
        return DynamoDbClient.builder()
                .region(Region.of(properties.getAws().getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
    
    @Bean
    @ConditionalOnMissingBean(KmsClient.class)
    @ConditionalOnProperty(prefix = "pagw.aws.kms", name = "enabled", havingValue = "true", matchIfMissing = true)
    public KmsClient kmsClient(PagwProperties properties) {
        return KmsClient.builder()
                .region(Region.of(properties.getAws().getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    /**
     * IdempotencyService is only created when DynamoDB client is available.
     * This allows services to function without idempotency checking in dev/local environments.
     */
    @Bean
    @ConditionalOnBean(DynamoDbClient.class)
    public IdempotencyService idempotencyService(DynamoDbClient dynamoDbClient, PagwProperties properties) {
        return new IdempotencyService(dynamoDbClient, properties);
    }
}
