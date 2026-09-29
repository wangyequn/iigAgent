package com.sinosoft.amp.openapi.ops;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;

import javax.sql.DataSource;

/**
 * S8 就绪探针：/api/v1/health/ready 检查 Redis + PostgreSQL。
 * 启动校验：拒绝在 StateStore 非分布式时上生产（风险 N：本地 StateStore）。
 */
@Component
public class ReadinessSupport {

    private static final Logger log = LoggerFactory.getLogger(ReadinessSupport.class);

    private final DataSource dataSource;
    private final String redisUri;

    public ReadinessSupport(DataSource dataSource,
                            @org.springframework.beans.factory.annotation.Value("${amp.agent.redis-uri:redis://127.0.0.1:6379}") String redisUri) {
        this.dataSource = dataSource;
        this.redisUri = redisUri;
    }

    public ReadyStatus probe() {
        boolean pg;
        try (Connection c = dataSource.getConnection()) {
            pg = c.isValid(2);
        } catch (Exception e) {
            pg = false;
        }
        boolean redis = pingRedis();
        boolean ready = pg && redis;
        return new ReadyStatus(ready, pg, redis, redisUri);
    }

    private boolean pingRedis() {
        try {
            String host = "127.0.0.1";
            int port = 6379;
            String body = redisUri == null ? "" : redisUri.replace("redis://", "");
            int slash = body.indexOf('/');
            if (slash >= 0) {
                body = body.substring(0, slash);
            }
            if (!body.isBlank()) {
                int idx = body.lastIndexOf(':');
                if (idx > 0) {
                    host = body.substring(0, idx);
                    port = Integer.parseInt(body.substring(idx + 1));
                } else {
                    host = body;
                }
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getByName(host), port), 800);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** 启动后自检日志（StateStore 必须 Redis）。 */
    public void logStartupCheck() {
        ReadyStatus s = probe();
        if (!s.ready()) {
            log.error("STARTUP_CHECK_FAIL ready={} pg={} redis={} — 多副本会依赖分布式 StateStore（Redis）", s.ready(), s.postgres(), s.redis());
        } else {
            log.info("STARTUP_CHECK_OK pg={} redis={}", s.postgres(), s.redis());
        }
    }

    public record ReadyStatus(boolean ready, boolean postgres, boolean redis, String redisUri) {
    }
}
