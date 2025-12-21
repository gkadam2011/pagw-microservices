package com.anthem.pagw.converter.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.net.URI;

/**
 * AWS Configuration for Local/Docker Development.
 * Configures AWS clients to use LocalStack endpoints.
 */
@Configuration
@Profile({"local", "docker"})
public class LocalAwsConfiguration {

    @Value("${pagw.aws.endpoint:http://localhost:4566}")
    private String awsEndpoint;

    @Value("${pagw.aws.region:us-east-1}")
    private String awsRegion;

    private StaticCredentialsProvider localCredentials() {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test"));
    }

    @Bean
    @Primary
    public S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(awsEndpoint))
                .region(Region.of(awsRegion))
                .credentialsProvider(localCredentials())
                .forcePathStyle(true)
                .build();
    }

    @Bean
    @Primary
    public SqsClient sqsClient() {
        return SqsClient.builder()
                .endpointOverride(URI.create(awsEndpoint))
                .region(Region.of(awsRegion))
                .credentialsProvider(localCredentials())
                .build();
    }

    @Bean
    @Primary
    public SqsAsyncClient sqsAsyncClient() {
        return SqsAsyncClient.builder()
                .endpointOverride(URI.create(awsEndpoint))
                .region(Region.of(awsRegion))
                .credentialsProvider(localCredentials())
                .build();
    }

    @Bean
    @Primary
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .endpointOverride(URI.create(awsEndpoint))
                .region(Region.of(awsRegion))
                .credentialsProvider(localCredentials())
                .build();
    }
}
