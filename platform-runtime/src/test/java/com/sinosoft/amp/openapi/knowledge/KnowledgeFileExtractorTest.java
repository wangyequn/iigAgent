package com.sinosoft.amp.openapi.knowledge;

import com.sinosoft.amp.openapi.catalog.CatalogException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeFileExtractorTest {

    @Test
    void extractsTextAndHtml() {
        assertEquals("hello",
                KnowledgeFileExtractor.extractText("a.txt", "hello".getBytes(StandardCharsets.UTF_8)));
        String html = KnowledgeFileExtractor.extractText(
                "a.html", "<html><script>x</script><body>Hi <b>there</b></body></html>"
                        .getBytes(StandardCharsets.UTF_8));
        assertTrue(html.contains("Hi"));
        assertTrue(html.contains("there"));
    }

    @Test
    void rejectsBinaryWithoutDify() {
        CatalogException e = assertThrows(CatalogException.class, () ->
                KnowledgeFileExtractor.extractText("a.pdf", new byte[] {1, 2, 3}));
        assertEquals("KNOWLEDGE_FILE_UNSUPPORTED", e.code());
    }
}
