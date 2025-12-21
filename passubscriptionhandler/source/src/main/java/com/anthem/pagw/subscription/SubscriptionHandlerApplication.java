package com.anthem.pagw.subscription;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * PAS Subscription Handler Application.
 * 
 * Handles FHIR R5-style Subscription notifications and client webhooks
 * for delivering async Prior Authorization responses to subscribers.
 * 
 * This is the final stage in the async pipeline:
 * pasrequestenricher → pasattachmenthandler → pasrequestconverter → 
 * pasapiconnector → pasresponsebuilder → pascallbackhandler → passubscriptionhandler
 */
@SpringBootApplication
@EnableAsync
@ComponentScan(basePackages = {
    "com.anthem.pagw.subscription",
    "com.anthem.pagw.core"
})
public class SubscriptionHandlerApplication {
    public static void main(String[] args) {
        SpringApplication.run(SubscriptionHandlerApplication.class, args);
    }
}
