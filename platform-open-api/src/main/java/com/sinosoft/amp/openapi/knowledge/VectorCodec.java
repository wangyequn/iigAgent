package com.sinosoft.amp.openapi.knowledge;

import java.util.Locale;

/** 变长向量序列化与余弦（兼容 hash 128 维与 HTTP embedding）。 */
public final class VectorCodec {

    private VectorCodec() {
    }

    public static double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) {
            return 0;
        }
        int n = Math.min(a.length, b.length);
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na <= 0 || nb <= 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    public static String toJson(double[] v) {
        if (v == null) {
            return "[]";
        }
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
            return new double[0];
        }
        String body = json.trim();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        if (body.isBlank()) {
            return new double[0];
        }
        String[] parts = body.split(",");
        double[] v = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                v[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException ignored) {
                v[i] = 0;
            }
        }
        return v;
    }
}
