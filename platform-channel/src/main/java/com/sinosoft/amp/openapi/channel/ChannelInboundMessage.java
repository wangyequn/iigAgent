package com.sinosoft.amp.openapi.channel;

/** Channel 入站消息（IM/办公入口统一模型）。 */
public record ChannelInboundMessage(
        String channelType,
        String externalUserId,
        String externalThreadId,
        String text,
        String rawPayload) {
}
