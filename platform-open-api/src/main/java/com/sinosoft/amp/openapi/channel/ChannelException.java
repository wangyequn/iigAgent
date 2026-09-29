package com.sinosoft.amp.openapi.channel;

import com.sinosoft.amp.openapi.catalog.CatalogException;

public class ChannelException extends CatalogException {

    public ChannelException(String code, String message) {
        super(code, message);
    }
}
