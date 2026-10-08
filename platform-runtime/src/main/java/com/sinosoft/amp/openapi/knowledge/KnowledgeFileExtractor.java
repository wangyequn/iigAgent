package com.sinosoft.amp.openapi.knowledge;

import com.sinosoft.amp.openapi.catalog.CatalogException;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Simple 路径：从上传文件抽取可入库文本。
 * Dify 路径直接把原文件交给 Dataset API 解析，不经过本类。
 */
public final class KnowledgeFileExtractor {

    private static final Set<String> TEXT_EXTS = Set.of(
            "txt", "md", "markdown", "csv", "tsv", "json", "log", "yml", "yaml", "html", "htm", "xml");

    private KnowledgeFileExtractor() {
    }

    public static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int i = filename.lastIndexOf('.');
        return i < 0 ? "" : filename.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isTextLike(String filename) {
        return TEXT_EXTS.contains(extensionOf(filename));
    }

    /** 抽取纯文本；不支持的格式抛 KNOWLEDGE_FILE_UNSUPPORTED。 */
    public static String extractText(String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new CatalogException("KNOWLEDGE_CONTENT_REQUIRED", "file is empty");
        }
        String ext = extensionOf(filename);
        if ("html".equals(ext) || "htm".equals(ext) || "xml".equals(ext)) {
            return stripTags(new String(bytes, StandardCharsets.UTF_8));
        }
        if (TEXT_EXTS.contains(ext) || ext.isEmpty()) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        throw new CatalogException("KNOWLEDGE_FILE_UNSUPPORTED",
                "simple provider 仅支持文本类文件（" + String.join("/", TEXT_EXTS) + "）：" + filename
                        + "；请启用 Dify 或粘贴文本内容");
    }

    private static String stripTags(String html) {
        return html.replaceAll("(?is)<script.*?>.*?</script>", " ")
                .replaceAll("(?is)<style.*?>.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
