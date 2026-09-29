package com.sinosoft.amp.common.api;

import java.time.Instant;

public record ApiError(String code, String message, String requestId, Instant timestamp) {

    public static ApiError of(String code, String message, String requestId) {
        return new ApiError(code, message, requestId, Instant.now());
    }
}
