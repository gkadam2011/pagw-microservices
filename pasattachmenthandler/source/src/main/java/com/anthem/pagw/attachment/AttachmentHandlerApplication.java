package com.anthem.pagw.attachment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {
    "com.anthem.pagw.attachment",
    "com.anthem.pagw.core"
})
public class AttachmentHandlerApplication {
    public static void main(String[] args) {
        SpringApplication.run(AttachmentHandlerApplication.class, args);
    }
}
