package com.sinosoft.amp.openapi.channel;

/** Channel 出站回复。 */
public record ChannelOutboundMessage(String text, boolean asCard) {
}
