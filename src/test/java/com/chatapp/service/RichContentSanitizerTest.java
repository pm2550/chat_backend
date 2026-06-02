package com.chatapp.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RichContentSanitizerTest {

    private final RichContentSanitizer sanitizer = new RichContentSanitizer();

    @Test
    void stripsRawHtmlButKeepsMarkdownSyntax() {
        String input = "## Title\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n<script>alert(1)</script>\nhello **world**";
        String out = sanitizer.sanitizeMarkdown(input);

        assertFalse(out.contains("<script"), "raw HTML must be stripped");
        assertFalse(out.toLowerCase().contains("alert(1)") && out.contains("<"), "script tag removed");
        // Markdown structure preserved.
        assertTrue(out.contains("## Title"));
        assertTrue(out.contains("| a | b |"));
        assertTrue(out.contains("|---|---|"));
        assertTrue(out.contains("**world**"));
        // Newlines preserved (prettyPrint disabled).
        assertTrue(out.contains("\n"));
    }

    @Test
    void preservesPlainTextWithComparisonOperators() {
        String input = "use a < b and b > c in code";
        // No HTML tags here, so jsoup-clean + unescape should round-trip the literals.
        String out = sanitizer.sanitizeMarkdown(input);
        assertTrue(out.contains("a < b"));
        assertTrue(out.contains("b > c"));
    }

    @Test
    void nullAndBlankPassThrough() {
        assertEquals(null, sanitizer.sanitizeMarkdown(null));
        assertEquals("", sanitizer.sanitizeMarkdown(""));
    }
}
