package dev.sorokin.eventnotificator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sorokin.eventcommon.kafka.EventChangeKafkaMessage;
import dev.sorokin.eventnotificator.dto.request.MarkNotificationsAsReadRequest;
import dev.sorokin.eventnotificator.mapper.NotificationEntityMapper;
import dev.sorokin.eventnotificator.model.domain.Notification;
import dev.sorokin.eventnotificator.model.entity.NotificationEntity;
import dev.sorokin.eventnotificator.model.entity.NotificationEventPayloadEntity;
import dev.sorokin.eventnotificator.repository.NotificationEventPayloadRepository;
import dev.sorokin.eventnotificator.repository.NotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class NotificationService {

    private final NotificationEventPayloadRepository notificationEventPayloadRepository;
    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;
    private final NotificationEntityMapper notificationEntityMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private static final String UNREAD_COUNTER_PREFIX = "notif:unread:";

    public NotificationService(
            NotificationEventPayloadRepository notificationEventPayloadRepository,
            NotificationRepository notificationRepository,
            ObjectMapper objectMapper,
            NotificationEntityMapper notificationEntityMapper,
            StringRedisTemplate stringRedisTemplate
    ) {
        this.notificationEventPayloadRepository = notificationEventPayloadRepository;
        this.notificationRepository = notificationRepository;
        this.objectMapper = objectMapper;
        this.notificationEntityMapper = notificationEntityMapper;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Transactional
    public void saveEventChange(EventChangeKafkaMessage eventChangeKafkaMessage) {
        if (notificationEventPayloadRepository.existsByMessageId(eventChangeKafkaMessage.messageId().toString())) {
            log.warn("Сообщение {} уже обработано, пропускаем!", eventChangeKafkaMessage.messageId());
            return;
        }

        var payload = notificationEventPayloadRepository.save(
                new NotificationEventPayloadEntity(
                        eventChangeKafkaMessage.messageId().toString(),
                        eventChangeKafkaMessage.eventType(),
                        eventChangeKafkaMessage.eventId(),
                        eventChangeKafkaMessage.occurredAt(),
                        eventChangeKafkaMessage.changedById(),
                        eventChangeKafkaMessage.ownerId(),
                        eventChangeKafkaMessage.eventName(),
                        buildPayloadJson(eventChangeKafkaMessage)
                )
        );

        List<NotificationEntity> notifications = eventChangeKafkaMessage.subscribers()
                .stream()
                .map(userId -> new NotificationEntity(
                        userId,
                        payload,
                        false,
                        LocalDateTime.now(),
                        null
                ))
                .toList();

        notificationRepository.saveAll(notifications);

        for (Long userId : eventChangeKafkaMessage.subscribers()) {
            try {
                stringRedisTemplate.opsForValue().increment(UNREAD_COUNTER_PREFIX + userId);
            } catch (Exception ex) {
                log.error("Redis is unavailable, counter for userId={} has not been increased", userId);
            }
        }
    }

    public List<Notification> findNotificationsByCurrentUser(Long userId) {
        return notificationRepository
                .findNotificationsByUserId(userId)
                .stream()
                .map(notificationEntityMapper::toDomain)
                .toList();
    }

    @Transactional
    public void markNotificationsAsRead(
            MarkNotificationsAsReadRequest readRequest,
            Long userId
    ) {
        int updatedCountNotifications = notificationRepository.markAsReadByUserIdAndIds(
                readRequest.notificationIds(),
                userId,
                LocalDateTime.now()
        );

        try {
            long actualUnread = notificationRepository.countByUserIdAndReadFalse(userId);
            stringRedisTemplate.opsForValue().set(UNREAD_COUNTER_PREFIX + userId, String.valueOf(actualUnread));
        } catch (Exception e) {
            log.error("Redis is unavailable, the counter for userId={} will be resynchronized later", userId);
        }

        log.info("Marked {} notifications as read for userId={}", updatedCountNotifications, userId);
    }

    private String buildPayloadJson(EventChangeKafkaMessage eventChangeKafkaMessage) {
        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("changes", eventChangeKafkaMessage.changes());
        try {
            return objectMapper.writeValueAsString(payloadMap);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to build payload JSON", e);
        }
    }

}