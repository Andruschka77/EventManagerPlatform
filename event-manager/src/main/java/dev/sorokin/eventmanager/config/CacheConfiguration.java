package dev.sorokin.eventmanager.config;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sorokin.eventmanager.model.domain.Event;
import dev.sorokin.eventmanager.model.domain.Location;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import java.time.Duration;
import java.util.List;

@Configuration
@EnableCaching
public class CacheConfiguration {

    @Bean
    public RedisTemplate<String, Event> redisTemplate(
            RedisConnectionFactory redisConnectionFactory,
            ObjectMapper objectMapper
    ) {
        RedisTemplate<String, Event> redisTemplate = new RedisTemplate<>();

        redisTemplate.setConnectionFactory(redisConnectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(new Jackson2JsonRedisSerializer<>(objectMapper, Event.class));

        redisTemplate.afterPropertiesSet();
        return redisTemplate;
    }

    @Bean
    public CacheManager cacheManager(
            RedisConnectionFactory redisConnectionFactory,
            ObjectMapper objectMapper
    ) {
        RedisCacheConfiguration baseConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(1L))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()));

        JavaType locationListType = objectMapper.getTypeFactory()
                .constructCollectionType(List.class, Location.class);

        RedisCacheConfiguration locationConfig = baseConfig.serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                        new Jackson2JsonRedisSerializer<>(objectMapper, Location.class)
                )
        );

        RedisCacheConfiguration locationAllConfig = baseConfig.serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                        new Jackson2JsonRedisSerializer<>(objectMapper, locationListType)
                )
        );

        RedisCacheConfiguration eventConfig = baseConfig.serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                        new Jackson2JsonRedisSerializer<>(objectMapper, Event.class)
                )
        );

        return RedisCacheManager.builder(redisConnectionFactory)
                .withCacheConfiguration("location", locationConfig)
                .withCacheConfiguration("locationAll", locationAllConfig)
                .withCacheConfiguration("event", eventConfig)
                .disableCreateOnMissingCache()
                .transactionAware()
                .build();
    }

}