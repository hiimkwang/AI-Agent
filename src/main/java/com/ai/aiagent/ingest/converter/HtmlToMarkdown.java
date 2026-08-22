package com.ai.aiagent.ingest.converter;

import com.ai.aiagent.ingest.Markdown;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.util.data.MutableDataSet;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@Slf4j
public class HtmlToMarkdown {

    private static final String NOISE_SELECTOR = String.join(",",
            "script", "style", "noscript", "iframe", "svg", "canvas",
            "nav", "footer", "header", "aside", "form", "button",
            "[role=navigation]", "[role=banner]", "[role=contentinfo]", "[aria-hidden=true]",
            ".nav", ".navbar", ".menu", ".sidebar", ".breadcrumb", ".footer", ".header",
            ".advertisement", ".ads", ".cookie", ".cookie-banner", ".popup", ".modal",
            "#nav", "#navbar", "#menu", "#sidebar", "#footer", "#header");

    private static final String[] MAIN_SELECTORS = {
            "article", "main", "[role=main]",
            ".post-content", ".entry-content", ".article-content", ".article-body",
            ".content", "#content", ".main-content", "#main-content", ".markdown-body"
    };

    private final FlexmarkHtmlConverter converter = FlexmarkHtmlConverter.builder(
            new MutableDataSet()
                    // Required: flexmark emits setext headings by default, which
                    // MarkdownChunker does not recognise as headings.
                    .set(FlexmarkHtmlConverter.SETEXT_HEADINGS, false)
                    .set(FlexmarkHtmlConverter.OUTPUT_UNKNOWN_TAGS, false)
                    .set(FlexmarkHtmlConverter.SKIP_ATTRIBUTES, true))
            .build();

    public String convert(byte[] bytes, String fileName, String baseUri) {
        String html = new String(bytes, StandardCharsets.UTF_8);
        return convert(html, fileName, baseUri);
    }

    public String convert(String html, String fileName, String baseUri) {
        Document doc = Jsoup.parse(html, baseUri == null ? "" : baseUri);

        doc.select(NOISE_SELECTOR).remove();
        doc.select("[style*=display:none]").remove();
        doc.select("[style*=display: none]").remove();
        doc.select("comment").remove();

        Element root = pickMainContent(doc);

        String title = doc.title();
        StringBuilder htmlOut = new StringBuilder();
        if (title != null && !title.isBlank() && root.select("h1").isEmpty()) {
            htmlOut.append("<h1>").append(Jsoup.clean(title, org.jsoup.safety.Safelist.none()))
                    .append("</h1>\n");
        }
        htmlOut.append(root.html());

        String markdown;
        try {
            markdown = converter.convert(htmlOut.toString());
        } catch (RuntimeException e) {
            log.warn("flexmark could not convert '{}' ({}), falling back to plain text.",
                    fileName, e.getMessage());
            markdown = root.wholeText();
        }
        return Markdown.normalize(markdown);
    }

    private Element pickMainContent(Document doc) {
        for (String selector : MAIN_SELECTORS) {
            Elements found = doc.select(selector);
            if (!found.isEmpty()) {
                Element best = found.stream()
                        .max(java.util.Comparator.comparingInt(e -> e.text().length()))
                        .orElse(null);
                if (best != null && best.text().length() > 200) {
                    return best;
                }
            }
        }
        Element body = doc.body();
        if (body == null) return doc;

        Element best = body;
        int bestLength = body.text().length();
        for (Element candidate : body.select("div, section")) {
            int length = candidate.text().length();
            if (length > bestLength * 0.6 && length < bestLength && candidate.select("p, li, td").size() >= 3) {
                best = candidate;
                bestLength = length;
            }
        }
        return best;
    }
}
