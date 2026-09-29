package com.sinosoft.amp.openapi.knowledge;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * S6 知识检索工具：返回带引用的原文片段，供回答时引用。
 */
public class KnowledgeToolset {

    private final KnowledgeService knowledge;
    private final String tenantId;

    public KnowledgeToolset(KnowledgeService knowledge, String tenantId) {
        this.knowledge = knowledge;
        this.tenantId = tenantId;
    }

    @Tool(
            name = "knowledge_search",
            description = "从平台知识库检索相关原文片段（带引用 [docId#chunkId]）。回答知识问题时必须调用本工具并引用片段。",
            readOnly = true,
            concurrencySafe = true)
    public String knowledgeSearch(
            @ToolParam(name = "query", required = true, description = "检索问题或关键词")
            Object query,
            @ToolParam(name = "topK", required = false, description = "返回条数，默认3")
            Object topK) {
        String q = query == null ? "" : String.valueOf(query).trim();
        if (q.isEmpty()) {
            return "{\"error\":\"query is required\"}";
        }
        int k = 3;
        if (topK != null) {
            try {
                k = Integer.parseInt(String.valueOf(topK).trim());
            } catch (NumberFormatException e) {
                k = 3;
            }
        }
        return knowledge.searchWithCitations(tenantId, q, k);
    }
}
