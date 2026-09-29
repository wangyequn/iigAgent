package com.sinosoft.amp.openapi.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S4 进程内业务工具（订单域）。不热加载 JAR；参数宽松解析，避免模型传参类型踩坑。
 */
public final class OrderBusinessTools {

    private OrderBusinessTools() {
    }

    public static AgentTool queryOrders() {
        return new AgentTool() {
            @Override
            public String getName() {
                return "query_orders";
            }

            @Override
            public String getDescription() {
                return "查询订单列表。参数 orderId 可选，为空返回最近订单。只读。";
            }

            @Override
            public Map<String, Object> getParameters() {
                Map<String, Object> orderId = new LinkedHashMap<>();
                orderId.put("type", "string");
                orderId.put("description", "订单号，可选");
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("orderId", orderId);
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", "object");
                schema.put("properties", props);
                return schema;
            }

            @Override
            public boolean isReadOnly() {
                return true;
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                String orderId = str(param.getInput() == null ? null : param.getInput().get("orderId"));
                String json = (orderId == null || orderId.isBlank())
                        ? "{\"orders\":[{\"id\":\"ORD-1001\",\"status\":\"PAID\",\"amount\":128.5},{\"id\":\"ORD-1002\",\"status\":\"PENDING\",\"amount\":39.9}]}"
                        : "{\"orders\":[{\"id\":\"" + orderId + "\",\"status\":\"PAID\",\"amount\":99.0}]}";
                return Mono.just(ToolResultBlock.text(json));
            }
        };
    }

    public static AgentTool placeOrder() {
        return new AgentTool() {
            @Override
            public String getName() {
                return "place_order";
            }

            @Override
            public String getDescription() {
                return "创建订单（危险写操作，需 HITL 人工确认）。参数：product 商品名，quantity 数量。";
            }

            @Override
            public Map<String, Object> getParameters() {
                Map<String, Object> product = new LinkedHashMap<>();
                product.put("type", "string");
                product.put("description", "商品名");
                Map<String, Object> quantity = new LinkedHashMap<>();
                quantity.put("type", "string");
                quantity.put("description", "数量，可选默认1");
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("product", product);
                props.put("quantity", quantity);
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", "object");
                schema.put("properties", props);
                schema.put("required", List.of("product"));
                return schema;
            }

            @Override
            public boolean isReadOnly() {
                return false;
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
                String product = str(input.get("product"));
                if (product == null || product.isBlank()) {
                    return Mono.just(ToolResultBlock.error("product is required"));
                }
                int qty = 1;
                Object q = input.get("quantity");
                if (q != null) {
                    try {
                        qty = Integer.parseInt(String.valueOf(q).trim());
                    } catch (NumberFormatException e) {
                        qty = 1;
                    }
                }
                String orderId = "ORD-" + (2000 + (int) (System.currentTimeMillis() % 8000));
                String json = "{\"orderId\":\"" + orderId + "\",\"product\":\"" + product
                        + "\",\"quantity\":" + qty
                        + ",\"status\":\"CREATED\",\"note\":\"place_order executed after HITL approve\"}";
                return Mono.just(ToolResultBlock.text(json));
            }
        };
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
