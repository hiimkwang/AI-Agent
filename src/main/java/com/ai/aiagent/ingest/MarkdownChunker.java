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

/**
 * Bam Markdown theo CAU TRUC, khong phai theo so ky tu.
 *
 * Cach cu cat cung 2000/500 ky tu nen thuong xuyen cat ngang giua mot dieu, mot
 * khoan hoac giua mot bang - chunk sinh ra mat nghia. Cach nay:
 *
 *   1) Tach tai lieu thanh BLOCK (doan van, danh sach, bang, code fence, heading).
 *      Bang va code fence la NGUYEN KHOI - khong bao gio bi cat giua.
 *   2) Gom block thanh SECTION theo heading, moi section mang mot "duong dan
 *      heading" (vd "Noi quy > Che do nghi phep > Nghi khong luong").
 *   3) Section qua ngan duoc gop voi section ke tiep (tranh chunk 1 dong vo nghia).
 *   4) Moi section duoc dong thanh PARENT <= parent-max-chars theo ranh gioi block.
 *   5) Moi parent duoc chia thanh CHILD <= child-max-chars theo ranh gioi CAU,
 *      co overlap.
 *
 * Ket qua: tim bang child nho (chinh xac), tra loi bang parent lon (du ngu canh),
 * va ca hai deu mang duong dan heading nen chunk khong con "mat goc".
 */
@Component
@Slf4j
public class MarkdownChunker {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    /**
     * Heading kieu setext: mot dong chu, dong duoi toan {@code =} (cap 1) hoac
     * {@code -} (cap 2). Ho tro dang nay de an toan voi file .md nguoi dung tu viet
     * hoac tu bo chuyen doi khac - neu khong, chunk se mat het cau truc muc.
     */
    private static final Pattern SETEXT_UNDERLINE = Pattern.compile("^(={2,}|-{2,})\\s*$");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?;:])\\s+(?=\\p{Lu}|\\d|-)");

    private final RagProperties props;

    public MarkdownChunker(RagProperties props) {
        this.props = props;
    }

    /**
     * @param index         thu tu chunk trong tai lieu
     * @param headingPath   duong dan heading, cach nhau bang " > "
     * @param content       child chunk - cai duoc TIM
     * @param parentContent parent chunk - cai duoc dua vao cau tra loi
     */
    public record Chunk(int index, String headingPath, String content, String parentContent) {

        /** Van ban dung de nhung / index full-text: co ca duong dan heading. */
        public String embedText(String generatedContext) {
            StringBuilder sb = new StringBuilder();
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
                        continue; // chunk trung lap trong cung tai lieu
                    }
                    chunks.add(new Chunk(index++, section.headingPath(), normalized, parent));
                }
            }
        }
        log.debug("Bam duoc {} chunk tu {} section ({} block).",
                chunks.size(), sections.size(), blocks.size());
        return chunks;
    }

    // ------------------------------------------------------------ 1) Block

    private List<Block> toBlocks(String markdown) {
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        List<Block> blocks = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        BlockType bufferType = BlockType.TEXT;
        boolean inCodeFence = false;

        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            String trimmed = line.strip();

            // Code fence: nguyen khoi tu ``` den ```
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

            // Setext: dong hien tai la chu, dong KE TIEP la === hoac ---
            if (!trimmed.isEmpty() && index + 1 < lines.length
                    && SETEXT_UNDERLINE.matcher(lines[index + 1].strip()).matches()
                    && !trimmed.startsWith("|")) {
                flush(blocks, buffer, bufferType, 0);
                int level = lines[index + 1].strip().startsWith("=") ? 1 : 2;
                blocks.add(new Block(BlockType.HEADING, level, trimmed));
                index++; // bo qua dong gach duoi
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

    private void flush(List<Block> blocks, StringBuilder buffer, BlockType type, int level) {
        if (buffer.length() == 0) return;
        String text = buffer.toString().strip();
        buffer.setLength(0);
        if (!text.isEmpty()) blocks.add(new Block(type, level, text));
    }

    // ---------------------------------------------------------- 2) Section

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

    /**
     * Gop section qua ngan vao section ke tiep, MIEN LA cung nhanh heading -
     * neu khong se tron lan noi dung cua hai muc khac nhau.
     */
    private List<Section> mergeTinySections(List<Section> sections) {
        int min = props.getChunking().getMinSectionChars();
        if (min <= 0) return sections;

        List<Section> out = new ArrayList<>();
        for (Section section : sections) {
            if (section.blocks().isEmpty()) continue;
            if (!out.isEmpty()) {
                Section previous = out.get(out.size() - 1);
                boolean sameBranch = sharePrefix(previous.headingPath(), section.headingPath());
                if (previous.length() < min && sameBranch) {
                    List<Block> merged = new ArrayList<>(previous.blocks());
                    merged.addAll(section.blocks());
                    // Giu duong dan CU THE HON de chunk khong mat vi tri
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

    private boolean sharePrefix(String a, String b) {
        if (a == null || b == null) return false;
        if (a.isEmpty() || b.isEmpty()) return true;
        String rootA = a.split(" > ")[0];
        String rootB = b.split(" > ")[0];
        return rootA.equals(rootB);
    }

    // ----------------------------------------------------------- 3) Parent

    /** Dong block vao parent, khong vuot parent-max-chars, khong cat giua block. */
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

    /** Block don le vuot gioi han: bang thi cat theo HANG va lap lai header. */
    private List<String> splitOversizedBlock(Block block, int max) {
        if (block.length() <= max || max <= 0) return List.of(block.text());

        if (block.type() == BlockType.TABLE) {
            return splitTable(block.text(), max);
        }
        return splitBySentences(block.text(), max, 0);
    }

    /**
     * Cat bang lon nhung LAP LAI dong header + dong phan cach o moi manh, nho vay
     * tung manh van doc duoc doc lap - dieu kien bat buoc de chunk bang co nghia.
     */
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

    // ------------------------------------------------------------ 4) Child

    private List<String> splitChildren(String parent) {
        int max = Math.max(200, props.getChunking().getChildMaxChars());
        int overlap = Math.max(0, Math.min(props.getChunking().getChildOverlapChars(), max / 2));
        if (parent.length() <= max) return List.of(parent);
        return splitBySentences(parent, max, overlap);
    }

    /**
     * Cat theo ranh gioi CAU (khong cat giua cau). Neu mot "cau" dai hon gioi han
     * - thuong la mot dong bang rat dai - thi moi cat cung theo do dai.
     */
    private List<String> splitBySentences(String text, int max, int overlap) {
        List<String> units = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.isBlank()) continue;
            // Dong bang / danh sach giu nguyen ven, khong tach thanh cau
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

    /** Dau tay chunk de khu trung: bo dau cach va chu hoa. */
    static String fingerprint(String text) {
        return text.replaceAll("\\s+", " ").toLowerCase().strip();
    }
}
