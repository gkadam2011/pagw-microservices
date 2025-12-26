package com.anthem.pagw.core.service;

import com.anthem.pagw.core.PagwProperties;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for idempotency checking using DynamoDB.
 * Prevents duplicate processing of the same request.
 * Note: This service is created as a @Bean in PagwCoreAutoConfiguration
 * and is only available when DynamoDB is enabled.
 */
@Slf4j
public class IdempotencyService {

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;
    private final long ttlSeconds;

    public IdempotencyService(DynamoDbClient dynamoDbClient, PagwProperties properties) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = properties.getAws().getDynamodb() != null 
            ? properties.getAws().getDynamodb().getIdempotencyTable() 
            : "pagw-idempotency";
        this.ttlSeconds = 86400; // 24 hours
    }

    public IdempotencyService(DynamoDbClient dynamoDbClient, String tableName, long ttlSeconds) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * Try to acquire a lock for processing. Returns true if this is a new request.
     */
    public boolean tryAcquire(String idempotencyKey, String serviceName) {
        try {
            long now = Instant.now().getEpochSecond();
            long ttl = now + ttlSeconds;

            Map<String, AttributeValue> item = new HashMap<>();
            item.put("pk", AttributeValue.builder().s(idempotencyKey).build());
            item.put("service", AttributeValue.builder().s(serviceName).build());
            item.put("status", AttributeValue.builder().s("PROCESSING").build());
            item.put("createdAt", AttributeValue.builder().n(String.valueOf(now)).build());
            item.put("ttl", AttributeValue.builder().n(String.valueOf(ttl)).build());

            PutItemRequest request = PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .conditionExpression("attribute_not_exists(pk)")
                    .build();

            dynamoDbClient.putItem(request);
            log.debug("Acquired idempotency lock for: {}", idempotencyKey);
            return true;
        } catch (ConditionalCheckFailedException e) {
            log.debug("Idempotency key already exists: {}", idempotencyKey);
            return false;
        } catch (Exception e) {
            log.error("Failed to check idempotency for: {}", idempotencyKey, e);
            // Fail open - allow processing if DynamoDB is unavailable
            return true;
        }
    }

    /**
     * Mark the request as completed successfully.
     */
    public void markCompleted(String idempotencyKey, String result) {
        try {
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("pk", AttributeValue.builder().s(idempotencyKey).build());

            Map<String, AttributeValueUpdate> updates = new HashMap<>();
            updates.put("status", AttributeValueUpdate.builder()
                    .value(AttributeValue.builder().s("COMPLETED").build())
                    .action(AttributeAction.PUT)
                    .build());
            updates.put("result", AttributeValueUpdate.builder()
                    .value(AttributeValue.builder().s(result).build())
                    .action(AttributeAction.PUT)
                    .build());
            updates.put("completedAt", AttributeValueUpdate.builder()
                    .value(AttributeValue.builder().n(String.valueOf(Instant.now().getEpochSecond())).build())
                    .action(AttributeAction.PUT)
                    .build());

            UpdateItemRequest request = UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(key)
                    .attributeUpdates(updates)
                    .build();

            dynamoDbClient.updateItem(request);
            log.debug("Marked idempotency key as completed: {}", idempotencyKey);
        } catch (Exception e) {
            log.error("Failed to mark idempotency key as completed: {}", idempotencyKey, e);
        }
    }

    /**
     * Mark the request as failed (allows retry).
     */
    public void markFailed(String idempotencyKey, String error) {
        try {
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("pk", AttributeValue.builder().s(idempotencyKey).build());

            DeleteItemRequest request = DeleteItemRequest.builder()
                    .tableName(tableName)
                    .key(key)
                    .build();

            dynamoDbClient.deleteItem(request);
            log.debug("Removed failed idempotency key (allowing retry): {}", idempotencyKey);
        } catch (Exception e) {
            log.error("Failed to remove idempotency key: {}", idempotencyKey, e);
        }
    }

    /**
     * Get the result of a previously completed request.
     */
    public String getResult(String idempotencyKey) {
        try {
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("pk", AttributeValue.builder().s(idempotencyKey).build());

            GetItemRequest request = GetItemRequest.builder()
                    .tableName(tableName)
                    .key(key)
                    .build();

            GetItemResponse response = dynamoDbClient.getItem(request);
            if (response.hasItem() && response.item().containsKey("result")) {
                return response.item().get("result").s();
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to get result for idempotency key: {}", idempotencyKey, e);
            return null;
        }
    }

    /**
     * Check and set idempotency key (backwards compatibility alias for tryAcquire).
     */
    public boolean checkAndSet(String idempotencyKey) {
        return tryAcquire(idempotencyKey, "unknown");
    }

    /**
     * Remove idempotency key (backwards compatibility alias for markFailed).
     */
    public void remove(String idempotencyKey) {
        markFailed(idempotencyKey, "removed");
    }
}
