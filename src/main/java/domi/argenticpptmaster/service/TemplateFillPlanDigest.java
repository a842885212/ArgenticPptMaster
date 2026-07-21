package domi.argenticpptmaster.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 计算 fill plan 内容的稳定摘要哈希。 */
final class TemplateFillPlanDigest {

    private TemplateFillPlanDigest() {
    }

    static String compute(byte[] planBytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(planBytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    static String compute(String planJson) {
        return compute(planJson.getBytes(StandardCharsets.UTF_8));
    }

    static String computeCombined(byte[] planBytes, byte[] serviceMetaBytes) {
        byte[] left = planBytes == null ? new byte[0] : planBytes;
        byte[] right = serviceMetaBytes == null ? new byte[0] : serviceMetaBytes;
        byte[] combined = new byte[left.length + 1 + right.length];
        System.arraycopy(left, 0, combined, 0, left.length);
        combined[left.length] = 0;
        System.arraycopy(right, 0, combined, left.length + 1, right.length);
        return compute(combined);
    }
}
