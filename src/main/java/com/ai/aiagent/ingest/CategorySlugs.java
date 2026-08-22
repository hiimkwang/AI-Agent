package com.ai.aiagent.ingest;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Derives a category slug from where a file sits under the ingest root, so one folder job can
 * cover a whole tree instead of one job per subfolder.
 *
 * <p>The slug has to come out byte-identical to what is already in the database, otherwise a
 * re-ingest creates a second document instead of overwriting - {@code doc_key} is
 * {@code category/fileName}. {@code CategorySlugsTest} pins the mapping against the real folder
 * names on the server.
 */
public final class CategorySlugs {

    private CategorySlugs() {
    }

    /**
     * @param root the ingest root the job was started on
     * @param file an absolute path to a file below {@code root}
     * @return the slug built from every directory between root and file, or {@code null} when the
     *         file sits directly in the root and there is nothing to derive from
     */
    public static String fromPath(Path root, Path file) {
        if (root == null || file == null) return null;
        Path relative;
        try {
            relative = root.toAbsolutePath().normalize()
                    .relativize(file.toAbsolutePath().normalize());
        } catch (IllegalArgumentException e) {
            return null;
        }
        int folders = relative.getNameCount() - 1;
        if (folders <= 0) return null;

        List<String> parts = new ArrayList<>();
        for (int i = 0; i < folders; i++) {
            String part = slugify(relative.getName(i).toString());
            if (!part.isEmpty()) parts.add(part);
        }
        return parts.isEmpty() ? null : String.join("-", parts);
    }

    /**
     * Vietnamese folder names have to survive: {@code Chatbot Giai Điệu} becomes
     * {@code chatbot-giai-dieu}. NFD strips the combining marks but leaves đ/Đ alone, so those
     * are mapped by hand.
     */
    public static String slugify(String name) {
        if (name == null) return "";
        String d = name.replace('Đ', 'D').replace('đ', 'd');
        d = Normalizer.normalize(d, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        d = d.toLowerCase(java.util.Locale.ROOT);
        d = d.replaceAll("[^a-z0-9]+", "-");
        return trimDashes(d);
    }

    private static String trimDashes(String s) {
        int from = 0;
        int to = s.length();
        while (from < to && s.charAt(from) == '-') from++;
        while (to > from && s.charAt(to - 1) == '-') to--;
        return s.substring(from, to);
    }
}
