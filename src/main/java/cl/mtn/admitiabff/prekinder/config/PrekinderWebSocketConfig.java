package cl.mtn.admitiabff.prekinder.config;

import cl.mtn.admitiabff.prekinder.realtime.PrekinderHandshakeInterceptor;
import cl.mtn.admitiabff.prekinder.realtime.PrekinderStompInterceptor;
import java.util.concurrent.Executors;
import java.util.List;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import cl.mtn.admitiabff.prekinder.realtime.PrekinderSocketRegistry;

@Configuration
@EnableWebSocketMessageBroker
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderWebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final PrekinderProperties properties;
    private final PrekinderHandshakeInterceptor handshakeInterceptor;
    private final PrekinderStompInterceptor stompInterceptor;
    private final ObjectMapper objectMapper;
    private final PrekinderSocketRegistry sockets;

    public PrekinderWebSocketConfig(PrekinderProperties properties,
                                    PrekinderHandshakeInterceptor handshakeInterceptor,
                                    PrekinderStompInterceptor stompInterceptor, ObjectMapper objectMapper,
                                    PrekinderSocketRegistry sockets) {
        this.properties = properties; this.handshakeInterceptor = handshakeInterceptor; this.stompInterceptor = stompInterceptor;
        this.objectMapper = objectMapper; this.sockets = sockets;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/api/prekinder/realtime")
            .setAllowedOrigins(properties.realtime().allowedOrigins().toArray(String[]::new))
            .addInterceptors(handshakeInterceptor);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        long heartbeat = properties.realtime().heartbeat().toMillis();
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.enableSimpleBroker("/queue")
            .setHeartbeatValue(new long[]{heartbeat, heartbeat})
            .setTaskScheduler(new ConcurrentTaskScheduler(Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "prekinder-heartbeat");
                thread.setDaemon(true);
                return thread;
            })));
    }

    @Override public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompInterceptor);
    }

    @Override public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(properties.realtime().maxFrameBytes())
            .setSendBufferSizeLimit(properties.realtime().maxFrameBytes() * 4)
            .setSendTimeLimit(5_000)
            .addDecoratorFactory(sockets::decorate);
    }

    @Override public boolean configureMessageConverters(List<MessageConverter> converters) {
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(objectMapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
        converters.add(converter);
        return false;
    }
}
