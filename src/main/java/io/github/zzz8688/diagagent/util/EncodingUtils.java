package io.github.zzz8688.diagagent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

public class EncodingUtils {

    private static final Logger log = LoggerFactory.getLogger(EncodingUtils.class);

    public static String ensureUtf8(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        log.info("EncodingUtils.ensureUtf8 called, text sample: {}", text.substring(0, Math.min(30, text.length())));

        String result = fixDoubleEncoding(text);
        if (result != null && !result.equals(text)) {
            log.info("Fixed encoding successfully, original: {}, fixed: {}", 
                text.substring(0, Math.min(20, text.length())), 
                result.substring(0, Math.min(20, result.length())));
            return result;
        }

        log.warn("Could not fix encoding, returning original");
        return text;
    }

    private static String fixDoubleEncoding(String text) {
        try {
            byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
            String fixed = new String(bytes, StandardCharsets.UTF_8);

            log.info("After ISO->UTF conversion: {}", fixed.substring(0, Math.min(30, fixed.length())));

            if (hasValidChinese(fixed)) {
                return fixed;
            }
        } catch (Exception e) {
            log.warn("fixDoubleEncoding failed: {}", e.getMessage());
        }
        return null;
    }

    private static boolean hasValidChinese(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        int chineseCount = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                chineseCount++;
            }
        }

        log.info("Chinese character count: {}", chineseCount);
        return chineseCount >= 3;
    }

    public static boolean containsChinese(String text) {
        return hasValidChinese(text);
    }
}
