package com.sinosoft.amp.openapi.fs;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * T9 工作区文件系统：remote=PostgreSQL BaseStore（多副本共享）| local=本地路径。
 */
@ConfigurationProperties(prefix = "amp.fs")
public record FsProperties(
        String mode,
        String isolationScope
) {
}
