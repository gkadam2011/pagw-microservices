package com.anthem.pagw.core.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Utility for generating unique PAGW IDs.
 * Format: PAGW-{YYYYMMDD}-{sequence}-{random}
 */
public class PagwIdGenerator {

    private static final AtomicLong sequence = new AtomicLong(0);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneId.of("UTC"));

    /**
     * Generate a unique PAGW ID.
     */
    public static String generate() {
        String date = DATE_FORMAT.format(Instant.now());
        long seq = sequence.incrementAndGet() % 100000;
        String random = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return String.format("PAGW-%s-%05d-%s", date, seq, random);
    }

    /**
     * Generate a unique PAGW ID with a prefix.
     */
    public static String generate(String prefix) {
        String date = DATE_FORMAT.format(Instant.now());
        long seq = sequence.incrementAndGet() % 100000;
        String random = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return String.format("%s-%s-%05d-%s", prefix, date, seq, random);
    }

    /**
     * Validate a PAGW ID format.
     */
    public static boolean isValid(String pagwId) {
        if (pagwId == null || pagwId.isEmpty()) {
            return false;
        }
        return pagwId.matches("PAGW-\\d{8}-\\d{5}-[A-Z0-9]{8}");
    }

    /**
     * Extract the date from a PAGW ID.
     */
    public static String extractDate(String pagwId) {
        if (!isValid(pagwId)) {
            throw new IllegalArgumentException("Invalid PAGW ID: " + pagwId);
        }
        return pagwId.substring(5, 13);
    }
}
