package com.sinosoft.amp.openapi.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * T8 会话日志：推 Loki + 本地文件兜底检索（DoD：日志可查会话）。
 */
@Service
public class SessionLogService {

    private static final Logger log = LoggerFactory.getLogger(SessionLogService.class);

    private final LokiLogClient loki;
    private final LokiProperties properties;

    public SessionLogService(LokiLogClient loki, LokiProperties properties) {
        this.loki = loki;
        this.properties = properties;
    }

    /**
     * 记录一条可检索会话日志（本地 slf4j + 可选 Loki push）。
     */
    public void sessionEvent(String level, String sessionId, String agentId, String userId, String message) {
        String line = String.format(
                "sessionEvent level=%s sessionId=%s agentId=%s userId=%s %s",
                level, sessionId, agentId, userId, message);
        log.info("{}", line);
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("sessionId", sessionId);
        labels.put("agentId", agentId);
        labels.put("level", level);
        loki.push(line, labels);
    }

    /**
     * 检索会话日志：Loki 优先；否则本地 log 文件按 sessionId 过滤。
     */
    public Map<String, Object> searchSession(String sessionId, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        List<String> lines;
        String source;
        if (loki.enabled()) {
            lines = loki.querySession(sessionId, limit);
            source = lines.isEmpty() ? "loki(empty)" : "loki";
            if (lines.isEmpty()) {
                lines = localSearch(sessionId, limit);
                if (!lines.isEmpty()) {
                    source = "local-fallback";
                }
            }
        } else {
            lines = localSearch(sessionId, limit);
            source = "local";
        }
        out.put("source", source);
        out.put("count", lines.size());
        out.put("lines", lines);
        out.put("loki", loki.status());
        return out;
    }

    List<String> localSearch(String sessionId, int limit) {
        String path = properties.localLogFile();
        if (path == null || path.isBlank()) {
            path = "logs/amp-open-api-18081.log";
        }
        Path p = Paths.get(path);
        if (!Files.isRegularFile(p)) {
            // 尝试相对工作目录 / C:\amp
            Path alt = Paths.get("C:/amp/logs/amp-open-api-18081.log");
            p = Files.isRegularFile(alt) ? alt : p;
        }
        if (!Files.isRegularFile(p)) {
            return List.of();
        }
        String needle = "sessionId=" + sessionId;
        List<String> hits = new ArrayList<>();
        try (Stream<String> stream = Files.lines(p, StandardCharsets.UTF_8)) {
            stream.filter(l -> l.contains(needle)).limit(Math.max(1, limit)).forEach(hits::add);
        } catch (IOException e) {
            log.warn("local log search failed {}", e.toString());
        }
        return hits;
    }
}
