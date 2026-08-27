package dev.sorokin.eventmanager.config;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sorokin.eventmanager.model.domain.Event;
import dev.sorokin.eventmanager.model.domain.Location;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
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
@Slf4j
public class CacheConfiguration implements CachingConfigurer {

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

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.error("Cache GET error [cache={}, key={}]: {}. Fallback to DB.",
                        cache.getName(), key, exception.toString());
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                log.error("Cache PUT error [cache={}, key={}]: {}. Skipping cache write.",
                        cache.getName(), key, exception.toString());
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                log.error("Cache EVICT error [cache={}, key={}]: {}. Skipping cache eviction.",
                        cache.getName(), key, exception.toString());
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.error("Cache CLEAR error [cache={}]: {}. Skipping cache clear.",
                        cache.getName(), exception.toString());
            }
        };
    }

}