package com.sinosoft.amp.openapi.knowledge;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * 一期 Simple 自建 embedding：字符 n-gram 哈希到定长向量，无需外部服务。
 * 后续可换成模型 embedding 服务，维度/实现可替换。
 */
public final class LocalHashEmbedder {

    public static final int DIM = 128;

    private LocalHashEmbedder() {
    }

    public static double[] embed(String text) {
        double[] v = new double[DIM];
        if (text == null || text.isBlank()) {
            return v;
        }
        String s = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        // unigram + bigram hashing
        accumulate(v, s, 1);
        accumulate(v, s, 2);
        accumulate(v, s, 3);
        // L2 normalize
        double norm = 0;
        for (double x : v) {
            norm += x * x;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < v.length; i++) {
                v[i] /= norm;
            }
        }
        return v;
    }

    public static double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }

    public static String toJson(double[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.ROOT, "%.6f", v[i]));
        }
        return sb.append(']').toString();
    }

    public static double[] fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new double[DIM];
        }
        String body = json.trim();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        String[] parts = body.split(",");
        double[] v = new double[DIM];
        for (int i = 0; i < parts.length && i < DIM; i++) {
            try {
                v[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException ignored) {
                v[i] = 0;
            }
        }
        return v;
    }

    private static void accumulate(double[] v, String s, int ngram) {
        if (s.length() < ngram) {
            return;
        }
        for (int i = 0; i + ngram <= s.length(); i++) {
            String gram = s.substring(i, i + ngram);
            CRC32 crc = new CRC32();
            crc.update(gram.getBytes(StandardCharsets.UTF_8));
            int idx = (int) (crc.getValue() % DIM);
            v[idx] += 1.0;
        }
    }
}
