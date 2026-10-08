package com.sinosoft.amp.openapi.knowledge;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识服务路由：amp.rag.dify.enabled=true 时走 Dify Dataset API，否则走 Simple（本地 PG）。
 * 其余模块依赖 {@link KnowledgeService}，不绑死具体实现。
 */
@Service
@Primary
public class KnowledgeServiceRouter implements KnowledgeService {

    private final KnowledgeService delegate;

    @Autowired
    public KnowledgeServiceRouter(
            SimpleKnowledgeService simple,
            DifyProperties difyProperties) {
        if (difyProperties != null && difyProperties.isConfigured()) {
            this.delegate = new DifyKnowledgeService(difyProperties);
        } else {
            this.delegate = simple;
        }
    }

    /** 测试/显式指定 delegate。 */
    public KnowledgeServiceRouter(KnowledgeService delegate) {
        this.delegate = delegate;
    }

    public KnowledgeService delegate() {
        return delegate;
    }

    @Override
    public String providerId() {
        return delegate.providerId();
    }

    @Override
    public SimpleKnowledgeService.IngestResult ingest(String tenantId, String title, String source, String content) {
        return delegate.ingest(tenantId, title, source, content);
    }

    @Override
    public SimpleKnowledgeService.IngestResult ingestFile(String tenantId, String filename, byte[] content, String source) {
        return delegate.ingestFile(tenantId, filename, content, source);
    }

    @Override
    public List<KnowledgeStore.DocRow> listDocuments(String tenantId) {
        return delegate.listDocuments(tenantId);
    }

    @Override
    public List<SimpleKnowledgeService.Hit> search(String tenantId, String query, int topK) {
        return delegate.search(tenantId, query, topK);
    }

    @Override
    public String searchWithCitations(String tenantId, String query, int topK) {
        return delegate.searchWithCitations(tenantId, query, topK);
    }
}
