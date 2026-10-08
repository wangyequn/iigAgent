package com.sinosoft.amp.openapi.orchestration;

import com.sinosoft.amp.openapi.catalog.CatalogException;

/** 二期编排域错误（错误码进 API 响应 code 字段）。 */
public class OrchestrationException extends CatalogException {

    public OrchestrationException(String code, String message) {
        super(code, message);
    }
}
