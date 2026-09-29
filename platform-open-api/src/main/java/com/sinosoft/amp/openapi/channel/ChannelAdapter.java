package com.sinosoft.amp.openapi.channel;

/**
 * Channel 适配 SPI：二期先落 Mock/飞书/企微其一，禁止在业务层绑死单一 IM。
 */
public interface ChannelAdapter {

    String channelType();

    /** 校验入站签名/来源；失败抛 ChannelException。 */
    void verifySignature(String signature, String timestamp, String rawBody);

    ChannelInboundMessage parseInbound(String rawBody);

    void deliver(String targetUser, ChannelOutboundMessage message);
}
