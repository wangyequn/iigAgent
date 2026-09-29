package com.sinosoft.amp.openapi.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Flux;

import java.util.function.Function;

/**
 * 全部工具被拒绝时停止本轮（AgentScope Java 官方配方，middleware 页示例）。
 * 默认行为是全拒后继续下一轮推理（模型只能看到 "Permission denied" 易空转）；
 * 本中间件在 onActing 观察 {@link AllToolsDeniedEvent} 并追发 {@link RequestStopEvent}，
 * 使本轮以 {@link GenerateReason#ALL_TOOLS_DENIED} 立即结束。
 */
public class StopOnAllDeniedMiddleware implements MiddlewareBase {

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent, RuntimeContext ctx, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return next.apply(input)
                .flatMap(event -> {
                    if (event instanceof AllToolsDeniedEvent) {
                        return Flux.just(
                                event,
                                new RequestStopEvent(
                                        "All tools denied by user",
                                        GenerateReason.ALL_TOOLS_DENIED));
                    }
                    return Flux.just(event);
                });
    }
}
