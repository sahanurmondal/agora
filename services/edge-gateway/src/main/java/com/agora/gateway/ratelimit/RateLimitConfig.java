package com.agora.gateway.ratelimit;

import com.agora.gateway.config.RateLimitProperties;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimitConfig {

    @Bean(destroyMethod = "shutdownNow")
    @ConditionalOnProperty(value = "gateway.ratelimit.enabled", havingValue = "true", matchIfMissing = true)
    public ManagedChannel rateLimiterChannel(RateLimitProperties props) {
        // Shaded builder referenced directly: ServiceLoader-based provider discovery
        // is unreliable inside the Boot fat jar.
        return NettyChannelBuilder.forTarget(props.addr()).usePlaintext().build();
    }

    @Bean
    @ConditionalOnProperty(value = "gateway.ratelimit.enabled", havingValue = "true", matchIfMissing = true)
    public RateLimitFilter rateLimitFilter(ManagedChannel rateLimiterChannel, RateLimitProperties props) {
        return new RateLimitFilter(new RateLimiterClient(rateLimiterChannel, props.deadlineMs()), props);
    }
}
