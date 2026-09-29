package com.sinosoft.amp.openapi.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * S4 进程内业务工具。使用 @Tool 注册为 ToolBase 子类，以便 PermissionEngine 检查 ASK/ALLOW。
 */
public class OrderToolset {

    @Tool(
            name = "query_orders",
            description = "查询订单列表。orderId 可选，为空返回最近订单。只读。",
            readOnly = true,
            concurrencySafe = true)
    public String queryOrders(
            @ToolParam(name = "orderId", required = false, description = "订单号，可选")
            Object orderId) {
        String id = orderId == null ? null : String.valueOf(orderId);
        if (id == null || id.isBlank() || "null".equals(id)) {
            return "{\"orders\":[{\"id\":\"ORD-1001\",\"status\":\"PAID\",\"amount\":128.5},{\"id\":\"ORD-1002\",\"status\":\"PENDING\",\"amount\":39.9}]}";
        }
        return "{\"orders\":[{\"id\":\"" + id + "\",\"status\":\"PAID\",\"amount\":99.0}]}";
    }

    @Tool(
            name = "place_order",
            description = "创建订单（危险写操作，执行前需人工确认）。参数 product 商品名，quantity 数量。",
            readOnly = false,
            concurrencySafe = false)
    public String placeOrder(
            @ToolParam(name = "product", required = true, description = "商品名")
            Object product,
            @ToolParam(name = "quantity", required = false, description = "数量，默认1")
            Object quantity) {
        if (product == null || String.valueOf(product).isBlank()) {
            return "{\"error\":\"product is required\"}";
        }
        String p = String.valueOf(product);
        int qty = 1;
        if (quantity != null && !String.valueOf(quantity).isBlank()) {
            try {
                qty = Integer.parseInt(String.valueOf(quantity).trim().replace(".0", ""));
            } catch (NumberFormatException e) {
                qty = 1;
            }
        }
        String orderId = "ORD-" + (2000 + (int) (System.currentTimeMillis() % 8000));
        return "{\"orderId\":\"" + orderId + "\",\"product\":\"" + p + "\",\"quantity\":" + qty
                + ",\"status\":\"CREATED\",\"note\":\"place_order executed after HITL approve\"}";
    }
}
