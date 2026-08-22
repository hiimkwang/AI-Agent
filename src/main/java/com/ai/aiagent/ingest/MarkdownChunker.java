package com.ai.aiagent.ingest;

import com.ai.aiagent.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class MarkdownChunker {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern SETEXT_UNDERLINE = Pattern.compile("^(={2,}|-{2,})\\s*$");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?;:])\\s+(?=\\p{Lu}|\\d|-)");

    private static final Pattern LEGAL_PART = Pattern.compile(
            "^phan\\s+(thu\\s+)?[\\p{L}\\d]+\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEGAL_CHAPTER = Pattern.compile(
            "^chuong\\s+([ivxlcdm]+|\\d+)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEGAL_SECTION = Pattern.compile(
            "^muc\\s+([ivxlcdm]+|\\d+|[a-z])\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEGAL_ARTICLE = Pattern.compile(
            "^dieu\\s+\\d+\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEGAL_APPENDIX = Pattern.compile(
            "^phu\\s+luc\\b.*", Pattern.CASE_INSENSITIVE);

    private static final int MAX_LEGAL_HEADING_CHARS = 200;

    private final RagProperties props;

    public MarkdownChunker(RagProperties props) {
        this.props = props;
    }

    public record Chunk(int index, String headingPath, String content, String parentContent) {

        public String embedText(String generatedContext) {
            return embedText(null, generatedContext);
        }

        public String embedText(String documentIdentity, String generatedContext) {
            StringBuilder sb = new StringBuilder();
            if (documentIdentity != null && !documentIdentity.isBlank()) {
                sb.append(documentIdentity).append('\n');
            }
            if (headingPath != null && !headingPath.isBlank()) {
                sb.append(headingPath).append('\n');
            }
            if (generatedContext != null && !generatedContext.isBlank()) {
                sb.append(generatedContext).append('\n');
            }
            sb.append(content);
            return sb.toString();
        }
    }

    public static String documentIdentity(String title, String docNumber,
                                          java.time.LocalDate effectiveDate) {
        List<String> parts = new ArrayList<>();
        if (title != null && !title.isBlank()) parts.add(title.strip());
        if (docNumber != null && !docNumber.isBlank()) parts.add(docNumber.strip());
        if (effectiveDate != null) parts.add("hiệu lực từ " + effectiveDate);
        return parts.isEmpty() ? "" : "[" + String.join(" — ", parts) + "]";
    }

    private enum BlockType { HEADING, TABLE, CODE, TEXT }

    private record Block(BlockType type, int headingLevel, String text) {
        int length() {
            return text.length();
        }
    }

    private record Section(String headingPath, List<Block> blocks) {
        int length() {
            return blocks.stream().mapToInt(Block::length).sum();
        }
    }

    public List<Chunk> chunk(String markdown) {
        if (markdown == null || markdown.isBlank()) return List.of();

        List<Block> blocks = toBlocks(markdown);
        List<Section> sections = toSections(blocks);
        sections = mergeTinySections(sections);

        List<Chunk> chunks = new ArrayList<>();
        Set<String> seen = props.getChunking().isDedupeWithinDocument() ? new LinkedHashSet<>() : null;
        int index = 0;

        for (Section section : sections) {
            for (String parent : packParents(section)) {
                for (String child : splitChildren(parent)) {
                    String normalized = child.strip();
                    if (normalized.isEmpty()) continue;
                    if (seen != null && !seen.add(fingerprint(normalized))) {
                        continue;
                    }
                    chunks.add(new Chunk(index++, section.headingPath(), normalized, parent));
                }
            }
        }
        log.debug("Chunked into {} chunks from {} sections ({} blocks).",
                chunks.size(), sections.size(), blocks.size());
        return chunks;
    }

    private List<Block> toBlocks(String markdown) {
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        List<Block> blocks = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        BlockType bufferType = BlockType.TEXT;
        boolean inCodeFence = false;

        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            String trimmed = line.strip();

            if (trimmed.startsWith("```")) {
                if (inCodeFence) {
                    buffer.append(line).append('\n');
                    flush(blocks, buffer, BlockType.CODE, 0);
                    inCodeFence = false;
                } else {
                    flush(blocks, buffer, bufferType, 0);
                    inCodeFence = true;
                    bufferType = BlockType.CODE;
                    buffer.append(line).append('\n');
                }
                continue;
            }
            if (inCodeFence) {
                buffer.append(line).append('\n');
                continue;
            }

            Matcher heading = HEADING.matcher(trimmed);
            if (heading.matches()) {
                flush(blocks, buffer, bufferType, 0);
                blocks.add(new Block(BlockType.HEADING,
                        heading.group(1).length(), heading.group(2).strip()));
                bufferType = BlockType.TEXT;
                continue;
            }

            if (!trimmed.isEmpty() && index + 1 < lines.length
                    && SETEXT_UNDERLINE.matcher(lines[index + 1].strip()).matches()
                    && !trimmed.startsWith("|")) {
                flush(blocks, buffer, bufferType, 0);
                int level = lines[index + 1].strip().startsWith("=") ? 1 : 2;
                blocks.add(new Block(BlockType.HEADING, level, trimmed));
                index++;
                bufferType = BlockType.TEXT;
                continue;
            }

            int legalLevel = props.getChunking().isLegalStructureEnabled()
                    ? legalHeadingLevel(trimmed) : 0;
            if (legalLevel > 0) {
                flush(blocks, buffer, bufferType, 0);
                blocks.add(new Block(BlockType.HEADING, legalLevel, trimmed));
                bufferType = BlockType.TEXT;
                continue;
            }

            boolean tableLine = trimmed.startsWith("|");
            if (tableLine && bufferType != BlockType.TABLE) {
                flush(blocks, buffer, bufferType, 0);
                bufferType = BlockType.TABLE;
            } else if (!tableLine && bufferType == BlockType.TABLE) {
                flush(blocks, buffer, BlockType.TABLE, 0);
                bufferType = BlockType.TEXT;
            }

            if (trimmed.isEmpty()) {
                if (bufferType == BlockType.TEXT) {
                    flush(blocks, buffer, BlockType.TEXT, 0);
                }
                continue;
            }
            buffer.append(line).append('\n');
        }
        flush(blocks, buffer, bufferType, 0);
        return blocks;
    }

    static int legalHeadingLevel(String line) {
        String trimmed = line == null ? "" : line.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LEGAL_HEADING_CHARS) return 0;

        String text = trimmed.replaceAll("^[*_#\\s]+", "").replaceAll("[*_\\s]+$", "");
        String plain = com.ai.aiagent.store.TsQueryBuilder.stripDiacritics(text)
                .toLowerCase().strip();

        if (LEGAL_PART.matcher(plain).matches()) return 2;
        if (LEGAL_CHAPTER.matcher(plain).matches()) return 3;
        if (LEGAL_APPENDIX.matcher(plain).matches()) return 3;
        if (LEGAL_SECTION.matcher(plain).matches()) return 4;
        if (LEGAL_ARTICLE.matcher(plain).matches()) return 5;
        return 0;
    }

    private void flush(List<Block> blocks, StringBuilder buffer, BlockType type, int level) {
        if (buffer.length() == 0) return;
        String text = buffer.toString().strip();
        buffer.setLength(0);
        if (!text.isEmpty()) blocks.add(new Block(type, level, text));
    }

    private List<Section> toSections(List<Block> blocks) {
        List<Section> sections = new ArrayList<>();
        String[] stack = new String[7];
        List<Block> current = new ArrayList<>();
        String currentPath = "";

        for (Block block : blocks) {
            if (block.type() == BlockType.HEADING) {
                if (!current.isEmpty()) {
                    sections.add(new Section(currentPath, new ArrayList<>(current)));
                    current.clear();
                }
                int level = Math.max(1, Math.min(6, block.headingLevel()));
                stack[level] = block.text();
                for (int deeper = level + 1; deeper <= 6; deeper++) {
                    stack[deeper] = null;
                }
                currentPath = joinPath(stack);
            } else {
                current.add(block);
            }
        }
        if (!current.isEmpty()) {
            sections.add(new Section(currentPath, current));
        }
        return sections;
    }

    private String joinPath(String[] stack) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            if (stack[i] == null || stack[i].isBlank()) continue;
            if (sb.length() > 0) sb.append(" > ");
            sb.append(stack[i].strip());
        }
        return sb.toString();
    }

    private List<Section> mergeTinySections(List<Section> sections) {
        int min = props.getChunking().getMinSectionChars();
        if (min <= 0) return sections;

        List<Section> out = new ArrayList<>();
        for (Section section : sections) {
            if (section.blocks().isEmpty()) continue;
            if (!out.isEmpty()) {
                Section previous = out.get(out.size() - 1);
                boolean sameBranch = sharePrefix(previous.headingPath(), section.headingPath());
                // Never merge across a legal unit boundary: a short Dieu folded into the
                // next one inherits its heading and the answer then cites the wrong article.
                boolean legalBoundary = isLegalUnit(previous.headingPath())
                        || isLegalUnit(section.headingPath());
                if (previous.length() < min && sameBranch && !legalBoundary) {
                    List<Block> merged = new ArrayList<>(previous.blocks());
                    merged.addAll(section.blocks());
                    String path = section.headingPath().length() >= previous.headingPath().length()
                            ? section.headingPath() : previous.headingPath();
                    out.set(out.size() - 1, new Section(path, merged));
                    continue;
                }
            }
            out.add(section);
        }
        return out;
    }

    private boolean isLegalUnit(String headingPath) {
        if (!props.getChunking().isLegalStructureEnabled()
                || headingPath == null || headingPath.isBlank()) {
            return false;
        }
        String[] parts = headingPath.split(" > ");
        return legalHeadingLevel(parts[parts.length - 1]) > 0;
    }

    private boolean sharePrefix(String a, String b) {
        if (a == null || b == null) return false;
        if (a.isEmpty() || b.isEmpty()) return true;
        String rootA = a.split(" > ")[0];
        String rootB = b.split(" > ")[0];
        return rootA.equals(rootB);
    }

    private List<String> packParents(Section section) {
        int max = Math.max(400, props.getChunking().getParentMaxChars());
        List<String> parents = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String prefix = section.headingPath().isBlank() ? "" : "## " + section.headingPath() + "\n\n";

        for (Block block : section.blocks()) {
            for (String piece : splitOversizedBlock(block, max - prefix.length())) {
                if (current.length() > 0 && current.length() + piece.length() + 2 > max - prefix.length()) {
                    parents.add(prefix + current.toString().strip());
                    current.setLength(0);
                }
                if (current.length() > 0) current.append("\n\n");
                current.append(piece);
            }
        }
        if (current.length() > 0) {
            parents.add(prefix + current.toString().strip());
        }
        return parents;
    }

    private List<String> splitOversizedBlock(Block block, int max) {
        if (block.length() <= max || max <= 0) return List.of(block.text());

        if (block.type() == BlockType.TABLE) {
            return splitTable(block.text(), max);
        }
        return splitBySentences(block.text(), max, 0);
    }

    private List<String> splitTable(String table, int max) {
        String[] lines = table.split("\n");
        if (lines.length <= 2) return List.of(table);

        String header = lines[0];
        String separator = lines.length > 1 && lines[1].contains("---") ? lines[1] : null;
        String head = separator == null ? header + "\n" : header + "\n" + separator + "\n";
        int firstDataRow = separator == null ? 1 : 2;

        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder(head);
        for (int i = firstDataRow; i < lines.length; i++) {
            if (current.length() + lines[i].length() + 1 > max && current.length() > head.length()) {
                parts.add(current.toString().strip());
                current = new StringBuilder(head);
            }
            current.append(lines[i]).append('\n');
        }
        if (current.length() > head.length()) parts.add(current.toString().strip());
        return parts.isEmpty() ? List.of(table) : parts;
    }

    private List<String> splitChildren(String parent) {
        int max = Math.max(200, props.getChunking().getChildMaxChars());
        int overlap = Math.max(0, Math.min(props.getChunking().getChildOverlapChars(), max / 2));
        if (parent.length() <= max) return List.of(parent);
        return splitBySentences(parent, max, overlap);
    }

    private List<String> splitBySentences(String text, int max, int overlap) {
        List<String> units = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.isBlank()) continue;
            if (line.strip().startsWith("|") || line.strip().startsWith("- ")
                    || line.strip().startsWith("* ")) {
                units.add(line);
            } else {
                units.addAll(List.of(SENTENCE_END.split(line)));
            }
        }

        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String unit : units) {
            if (unit.length() > max) {
                if (current.length() > 0) {
                    out.add(current.toString().strip());
                    current.setLength(0);
                }
                for (int i = 0; i < unit.length(); i += max) {
                    out.add(unit.substring(i, Math.min(unit.length(), i + max)).strip());
                }
                continue;
            }
            if (current.length() + unit.length() + 1 > max && current.length() > 0) {
                out.add(current.toString().strip());
                current.setLength(0);
                if (overlap > 0 && !out.isEmpty()) {
                    String previous = out.get(out.size() - 1);
                    String tail = previous.length() <= overlap
                            ? previous : previous.substring(previous.length() - overlap);
                    current.append(tail).append(' ');
                }
            }
            if (current.length() > 0 && current.charAt(current.length() - 1) != ' ') {
                current.append('\n');
            }
            current.append(unit);
        }
        if (current.length() > 0) out.add(current.toString().strip());
        return out.stream().filter(s -> !s.isBlank()).toList();
    }

    static String fingerprint(String text) {
        return text.replaceAll("\\s+", " ").toLowerCase().strip();
    }
}
