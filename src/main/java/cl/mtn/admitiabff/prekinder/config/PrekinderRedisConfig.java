package cl.mtn.admitiabff.prekinder.config;

import cl.mtn.admitiabff.prekinder.realtime.PrekinderEventFanout;
import cl.mtn.admitiabff.prekinder.realtime.PrekinderRevocationListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderRedisConfig {
    @Bean
    RedisMessageListenerContainer prekinderRedisListener(RedisConnectionFactory connectionFactory,
                                                         PrekinderEventFanout fanout,
                                                         PrekinderRevocationListener revocations) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(fanout, new ChannelTopic(PrekinderEventFanout.CHANNEL));
        container.addMessageListener(revocations, new ChannelTopic(PrekinderRevocationListener.CHANNEL));
        return container;
    }
}
