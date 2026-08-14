package com.ai.aiagent.store;

/** Chuyen doi giua {@code float[]} va literal cua kieu {@code vector} trong pgvector. */
public final class Vectors {

    private Vectors() {
    }

    /** {@code [0.1,-0.2,...]} - dang literal de cast {@code ?::vector}. */
    public static String toLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 9 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    public static float[] fromLiteral(String literal) {
        if (literal == null || literal.length() < 2) return new float[0];
        String body = literal.substring(1, literal.length() - 1);
        if (body.isBlank()) return new float[0];
        String[] parts = body.split(",");
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Float.parseFloat(parts[i].trim());
        }
        return out;
    }

    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0;
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** Literal mang text cua Postgres, dung cho {@code ?::text[]}. */
    public static String toTextArrayLiteral(java.util.Collection<String> values) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String v : values) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(v == null ? "" : v.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }
}
