package com.agora.chat;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class PresenceController {

    private final ChatHandler handler;

    public PresenceController(ChatHandler handler) {
        this.handler = handler;
    }

    @GetMapping("/api/v1/presence/{user}")
    public Map<String, Object> presence(@PathVariable String user) {
        return Map.of("user", user, "online", handler.isOnline(user));
    }
}
