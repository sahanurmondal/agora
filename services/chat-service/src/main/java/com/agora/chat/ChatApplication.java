package com.agora.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@SpringBootApplication
@EnableWebSocket
public class ChatApplication implements WebSocketConfigurer {

    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }

    private final ChatHandler handler;

    public ChatApplication(ChatHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws").setAllowedOrigins("*");
    }

    /** Every node subscribes to every room channel; local sessions filter. */
    @Bean
    RedisMessageListenerContainer chatSubscriber(RedisConnectionFactory cf, ChatHandler handler) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(cf);
        container.addMessageListener((message, pattern) ->
                        handler.deliverLocal(new String(message.getBody())),
                new PatternTopic("chat:room:*"));
        return container;
    }
}
