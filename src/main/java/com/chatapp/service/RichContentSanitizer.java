package com.chatapp.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Service;

/**
 * Sanitizes untrusted (LLM / external bot) markdown before it is stored and later
 * rendered client-side. Bot output is untrusted, so we strip ALL raw HTML — the
 * markdown renderer must never see inline HTML (stored-XSS defense). Markdown syntax
 * itself (tables / bold / headings / lists / code / links) is preserved.
 */
@Service
public class RichContentSanitizer {

    private static final int MAX_MARKDOWN_LENGTH = 16_000;

    public String sanitizeMarkdown(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        // prettyPrint(false) preserves the newlines/whitespace that markdown structure
        // (tables, code fences, lists) depends on.
        Document.OutputSettings settings = new Document.OutputSettings()
                .prettyPrint(false)
                .charset("UTF-8");
        String stripped = Jsoup.clean(content, "", Safelist.none(), settings);
        // Jsoup escapes surviving text entities (&lt; &amp; …); markdown needs them literal.
        stripped = Parser.unescapeEntities(stripped, false);
        if (stripped.length() > MAX_MARKDOWN_LENGTH) {
            stripped = stripped.substring(0, MAX_MARKDOWN_LENGTH);
        }
        return stripped;
    }
}
