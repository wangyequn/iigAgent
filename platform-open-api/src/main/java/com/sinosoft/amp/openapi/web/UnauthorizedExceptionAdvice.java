package com.sinosoft.amp.openapi.web;

import com.sinosoft.amp.common.api.ApiError;
import com.sinosoft.amp.openapi.security.UnauthorizedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

/**
 * 全局 RBAC/鉴权拒绝 → 403 {@code INSUFFICIENT_SCOPE}（兼容一期错误体）。
 * 控制器内已有同类型 Handler 时优先本地；此处兜住 Iam/Admin 等未声明的入口。
 */
@RestControllerAdvice
public class UnauthorizedExceptionAdvice {

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiError> handle(UnauthorizedException e) {
        String message = e.getMessage() == null ? "forbidden" : e.getMessage();
        String code = message.startsWith("RBAC_DENIED") ? "RBAC_DENIED" : "INSUFFICIENT_SCOPE";
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(code, message, UUID.randomUUID().toString()));
    }
}
