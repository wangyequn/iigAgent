package com.sinosoft.amp.openapi.catalog;

/**
 * 发布/切流时抛出的目录错误。
 */
public class CatalogException extends RuntimeException {

    private final String code;

    public CatalogException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
