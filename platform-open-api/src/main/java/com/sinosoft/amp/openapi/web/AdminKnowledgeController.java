package com.sinosoft.amp.openapi.web;

import com.sinosoft.amp.common.api.ApiError;
import com.sinosoft.amp.openapi.catalog.CatalogException;
import com.sinosoft.amp.openapi.knowledge.KnowledgeService;
import com.sinosoft.amp.openapi.knowledge.KnowledgeStore;
import com.sinosoft.amp.openapi.knowledge.SimpleKnowledgeService;
import com.sinosoft.amp.openapi.security.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * S6 知识库 Admin：文档入库 / 检索（带引用）。Provider 由 KnowledgeService 路由（simple|dify）。
 */
@RestController
@RequestMapping("/api/v1/admin/knowledge")
public class AdminKnowledgeController {

    private final KnowledgeService knowledge;

    public AdminKnowledgeController(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", knowledge.providerId());
        return body;
    }

    @PostMapping("/documents")
    public Map<String, Object> upload(@Valid @RequestBody IngestRequest request) {
        String tenant = AuthContext.requireTenantId();
        SimpleKnowledgeService.IngestResult r =
                knowledge.ingest(tenant, request.title(), request.source(), request.content());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("documentId", r.documentId());
        body.put("title", r.title());
        body.put("chunkCount", r.chunkCount());
        body.put("tenantId", tenant);
        body.put("provider", knowledge.providerId());
        return body;
    }

    /** 多文件上传：Dify 走原文件解析（PDF/DOCX/PPTX/XLSX/图片等）；Simple 抽取文本类。 */
    @PostMapping("/documents/file")
    public Map<String, Object> uploadFiles(
            @RequestParam("files") org.springframework.web.multipart.MultipartFile[] files,
            @RequestParam(value = "source", required = false, defaultValue = "console") String source) {
        String tenant = AuthContext.requireTenantId();
        List<Map<String, Object>> uploaded = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        if (files != null) {
            for (org.springframework.web.multipart.MultipartFile file : files) {
                if (file == null || file.isEmpty()) {
                    continue;
                }
                String filename = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
                try {
                    SimpleKnowledgeService.IngestResult r = knowledge.ingestFile(
                            tenant, filename, file.getBytes(), source);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("filename", filename);
                    m.put("documentId", r.documentId());
                    m.put("title", r.title());
                    m.put("chunkCount", r.chunkCount());
                    uploaded.add(m);
                } catch (Exception e) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("filename", filename);
                    m.put("error", e.getMessage());
                    failed.add(m);
                }
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", knowledge.providerId());
        body.put("uploaded", uploaded);
        body.put("failed", failed);
        body.put("tenantId", tenant);
        return body;
    }

    @GetMapping("/documents")
    public Map<String, Object> list() {
        String tenant = AuthContext.requireTenantId();
        List<Map<String, Object>> docs = new ArrayList<>();
        for (KnowledgeStore.DocRow d : knowledge.listDocuments(tenant)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.id());
            m.put("title", d.title());
            m.put("source", d.source());
            m.put("status", d.status());
            m.put("createdAt", d.createdAt());
            docs.add(m);
        }
        return Map.of("tenantId", tenant, "documents", docs, "provider", knowledge.providerId());
    }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam String query, @RequestParam(defaultValue = "3") int topK) {
        String tenant = AuthContext.requireTenantId();
        List<SimpleKnowledgeService.Hit> hits = knowledge.search(tenant, query, topK);
        List<Map<String, Object>> list = new ArrayList<>();
        for (SimpleKnowledgeService.Hit h : hits) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("citation", "[" + h.documentId() + "#" + h.chunkId() + "]");
            m.put("documentId", h.documentId());
            m.put("chunkId", h.chunkId());
            m.put("score", h.score());
            m.put("text", h.text());
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("hits", list);
        out.put("raw", knowledge.searchWithCitations(tenant, query, topK));
        out.put("provider", knowledge.providerId());
        return out;
    }

    @ExceptionHandler(CatalogException.class)
    public ResponseEntity<ApiError> handleCatalog(CatalogException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(e.code(), e.getMessage(), requestId()));
    }

    private static String requestId() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null && attrs.getRequest().getHeader("X-Request-Id") != null) {
            return attrs.getRequest().getHeader("X-Request-Id");
        }
        return UUID.randomUUID().toString();
    }

    public record IngestRequest(
            String title,
            String source,
            @NotBlank String content
    ) {
    }
}
