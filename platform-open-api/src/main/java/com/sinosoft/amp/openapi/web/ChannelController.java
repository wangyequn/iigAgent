package com.sinosoft.amp.openapi.web;

import com.sinosoft.amp.openapi.channel.ChannelService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Channel 入站 Webhook（签名校验在 Adapter 内完成，不走 API Key）。 */
@RestController
@RequestMapping("/api/v1/channels")
public class ChannelController {

    private final ChannelService channels;

    public ChannelController(ChannelService channels) {
        this.channels = channels;
    }

    @PostMapping("/{channelType}/webhook")
    public Map<String, Object> webhook(
            @PathVariable String channelType,
            @RequestHeader(value = "X-Channel-Token", required = false) String token,
            @RequestBody String rawBody) {
        return channels.handleInbound(channelType, token, rawBody);
    }
}
