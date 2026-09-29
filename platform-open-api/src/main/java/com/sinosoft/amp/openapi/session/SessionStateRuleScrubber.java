package com.sinosoft.amp.openapi.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPooled;

import java.util.Locale;
import java.util.Set;

/**
 * 会话状态权限上下文重同步（建议规则/管理台规则变更后调用）。
 *
 * <p>背景（运行态实证）：permission_rule 落库 + 驱逐实例只影响「重建时注入的 context」，
 * 引擎实际判定读的是 Redis {@code agent_state.permission_context}，且 builder 规则会合并
 * 进状态但状态里既有的 ask_rules 不会被覆盖——引擎按 DENY→ASK→ALLOW 优先命中陈旧 ASK，
 * 导致新 ALLOW 规则被旧会话状态压制（落库、驱逐都完成后仍再次弹确认）。
 * 本组件把指定工具在全部会话状态中的 ask/allow/deny 条目清掉，再按当前行为写回，
 * 使状态与 permission_rule 表一致，跨既有会话立即生效。</p>
 */
@Component
public class SessionStateRuleScrubber {

    private static final Logger log = LoggerFactory.getLogger(SessionStateRuleScrubber.class);

    private final JedisPooled jedis;
    private final String stateKeyPrefix;
    private final ObjectMapper objectMapper;

    public SessionStateRuleScrubber(
            @Value("${amp.agent.redis-uri:redis://127.0.0.1:6379}") String redisUri,
            @Value("${amp.agent.state-key-prefix:amp:demo:session:}") String stateKeyPrefix,
            ObjectMapper objectMapper) {
        this.jedis = new JedisPooled(redisUri);
        this.stateKeyPrefix = stateKeyPrefix == null || stateKeyPrefix.isBlank()
                ? "amp:demo:session:" : stateKeyPrefix;
        this.objectMapper = objectMapper;
    }

    /**
     * 将 toolName 在所有命中前缀的会话状态中的权限条目重同步为 behavior。
     *
     * @param behavior ALLOW / ASK / DENY；空表示无规则（仅清除，回落 mode 默认）
     * @return 被修改的状态数（单键异常跳过，不外抛）
     */
    public int resyncTool(String toolName, String behavior) {
        if (toolName == null || toolName.isBlank()) {
            return 0;
        }
        String target = behavior == null || behavior.isBlank()
                ? null : behavior.trim().toLowerCase(Locale.ROOT);
        if (target != null && !target.equals("allow") && !target.equals("ask") && !target.equals("deny")) {
            log.warn("resync skip unknown behavior tool={} behavior={}", toolName, behavior);
            return 0;
        }
        int updated = 0;
        try {
            Set<String> keys = jedis.keys(stateKeyPrefix + "*:agent_state");
            for (String key : keys) {
                try {
                    String raw = jedis.get(key);
                    if (raw == null || raw.isBlank()) {
                        continue;
                    }
                    JsonNode root = objectMapper.readTree(raw);
                    JsonNode pc = root.get("permission_context");
                    if (pc == null || !pc.isObject()) {
                        continue;
                    }
                    ObjectNode pcNode = (ObjectNode) pc;
                    boolean changed = false;
                    for (String bucket : new String[] {"ask_rules", "allow_rules", "deny_rules"}) {
                        JsonNode node = pcNode.get(bucket);
                        if (node != null && node.isObject() && node.has(toolName)) {
                            ((ObjectNode) node).remove(toolName);
                            changed = true;
                        }
                    }
                    if (target != null) {
                        String bucket = switch (target) {
                            case "allow" -> "allow_rules";
                            case "ask" -> "ask_rules";
                            default -> "deny_rules";
                        };
                        ObjectNode bucketNode = (ObjectNode) pcNode.get(bucket);
                        if (bucketNode == null) {
                            bucketNode = pcNode.putObject(bucket);
                        }
                        ArrayNode rules = bucketNode.putArray(toolName);
                        ObjectNode rule = rules.addObject();
                        rule.put("tool_name", toolName);
                        rule.putNull("rule_content");
                        rule.put("behavior", target);
                        rule.put("source", "session");
                        changed = true;
                    }
                    if (changed) {
                        jedis.set(key, objectMapper.writeValueAsString(root));
                        updated++;
                    }
                } catch (RuntimeException | java.io.IOException e) {
                    log.warn("resync session state skipped key={}: {}", key, e.toString());
                }
            }
        } catch (RuntimeException e) {
            log.warn("resync permission context failed tool={}: {}", toolName, e.toString());
        }
        if (updated > 0) {
            log.info("session permission contexts resynced tool={} behavior={} updated={}",
                    toolName, target, updated);
        }
        return updated;
    }

    /** 会话状态里的 mode 与 permission-mode 变更同步（状态既有 mode 不会被 builder 覆盖）。 */
    public int resyncMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return 0;
        }
        String target = mode.trim().toLowerCase(Locale.ROOT);
        int updated = 0;
        try {
            Set<String> keys = jedis.keys(stateKeyPrefix + "*:agent_state");
            for (String key : keys) {
                try {
                    String raw = jedis.get(key);
                    if (raw == null || raw.isBlank()) {
                        continue;
                    }
                    JsonNode root = objectMapper.readTree(raw);
                    JsonNode pc = root.get("permission_context");
                    if (pc == null || !pc.isObject()) {
                        continue;
                    }
                    JsonNode current = pc.get("mode");
                    if (current != null && target.equals(current.asText())) {
                        continue;
                    }
                    ((ObjectNode) pc).put("mode", target);
                    jedis.set(key, objectMapper.writeValueAsString(root));
                    updated++;
                } catch (RuntimeException | java.io.IOException e) {
                    log.warn("resync mode skipped key={}: {}", key, e.toString());
                }
            }
        } catch (RuntimeException e) {
            log.warn("resync mode failed: {}", e.toString());
        }
        if (updated > 0) {
            log.info("session permission mode resynced mode={} updated={}", target, updated);
        }
        return updated;
    }
}
