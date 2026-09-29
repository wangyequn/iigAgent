package com.sinosoft.amp.openapi.automation;

import com.sinosoft.amp.openapi.catalog.CatalogException;

public class AutomationException extends CatalogException {

    public AutomationException(String code, String message) {
        super(code, message);
    }
}
