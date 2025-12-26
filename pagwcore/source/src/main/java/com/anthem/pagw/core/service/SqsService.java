package com.anthem.pagw.core.service;

import com.anthem.pagw.core.model.PagwMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for SQS operations - send, receive, delete messages.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqsService {

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;

    public String sendMessage(String queueUrl, PagwMessage message) {
        try {
            String messageBody = objectMapper.writeValueAsString(message);
            
            SendMessageRequest.Builder requestBuilder = SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(messageBody);

            // Only set FIFO parameters for FIFO queues (queue URL ends with .fifo)
            if (isFifoQueue(queueUrl)) {
                requestBuilder.messageGroupId(message.getPagwId());
                requestBuilder.messageDeduplicationId(message.getMessageId());
            }

            SendMessageResponse response = sqsClient.sendMessage(requestBuilder.build());
            log.debug("Sent message to {}: {}", queueUrl, response.messageId());
            return response.messageId();
        } catch (Exception e) {
            log.error("Failed to send message to queue: {}", queueUrl, e);
            throw new RuntimeException("Failed to send SQS message", e);
        }
    }

    /**
     * Check if a queue URL is for a FIFO queue.
     */
    private boolean isFifoQueue(String queueUrl) {
        return queueUrl != null && queueUrl.endsWith(".fifo");
    }

    public String sendRawMessage(String queueUrl, String messageBody, String groupId, String deduplicationId) {
        try {
            SendMessageRequest.Builder requestBuilder = SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(messageBody);

            if (groupId != null) {
                requestBuilder.messageGroupId(groupId);
            }
            if (deduplicationId != null) {
                requestBuilder.messageDeduplicationId(deduplicationId);
            }

            SendMessageResponse response = sqsClient.sendMessage(requestBuilder.build());
            log.debug("Sent raw message to {}: {}", queueUrl, response.messageId());
            return response.messageId();
        } catch (Exception e) {
            log.error("Failed to send raw message to queue: {}", queueUrl, e);
            throw new RuntimeException("Failed to send SQS message", e);
        }
    }

    public List<Message> receiveMessages(String queueUrl, int maxMessages, int waitTimeSeconds) {
        try {
            ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(maxMessages)
                    .waitTimeSeconds(waitTimeSeconds)
                    .attributeNames(QueueAttributeName.ALL)
                    .messageAttributeNames("All")
                    .build();

            ReceiveMessageResponse response = sqsClient.receiveMessage(request);
            return response.messages();
        } catch (Exception e) {
            log.error("Failed to receive messages from queue: {}", queueUrl, e);
            throw new RuntimeException("Failed to receive SQS messages", e);
        }
    }

    public void deleteMessage(String queueUrl, String receiptHandle) {
        try {
            DeleteMessageRequest request = DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(receiptHandle)
                    .build();

            sqsClient.deleteMessage(request);
            log.debug("Deleted message from {}", queueUrl);
        } catch (Exception e) {
            log.error("Failed to delete message from queue: {}", queueUrl, e);
            throw new RuntimeException("Failed to delete SQS message", e);
        }
    }

    public void deleteMessages(String queueUrl, List<Message> messages) {
        if (messages.isEmpty()) return;

        try {
            List<DeleteMessageBatchRequestEntry> entries = messages.stream()
                    .map(m -> DeleteMessageBatchRequestEntry.builder()
                            .id(m.messageId())
                            .receiptHandle(m.receiptHandle())
                            .build())
                    .collect(Collectors.toList());

            DeleteMessageBatchRequest request = DeleteMessageBatchRequest.builder()
                    .queueUrl(queueUrl)
                    .entries(entries)
                    .build();

            sqsClient.deleteMessageBatch(request);
            log.debug("Deleted {} messages from {}", messages.size(), queueUrl);
        } catch (Exception e) {
            log.error("Failed to batch delete messages from queue: {}", queueUrl, e);
            throw new RuntimeException("Failed to batch delete SQS messages", e);
        }
    }

    public String getQueueUrl(String queueName) {
        try {
            GetQueueUrlRequest request = GetQueueUrlRequest.builder()
                    .queueName(queueName)
                    .build();
            return sqsClient.getQueueUrl(request).queueUrl();
        } catch (Exception e) {
            log.error("Failed to get queue URL for: {}", queueName, e);
            throw new RuntimeException("Failed to get queue URL", e);
        }
    }
}
