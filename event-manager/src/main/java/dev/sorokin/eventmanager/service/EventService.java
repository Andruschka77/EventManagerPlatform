package dev.sorokin.eventmanager.service;

import dev.sorokin.eventcommon.exception.AccessDeniedException;
import dev.sorokin.eventcommon.kafka.ChangeItem;
import dev.sorokin.eventcommon.kafka.EventChangeKafkaMessage;
import dev.sorokin.eventmanager.dto.request.EventSearchRequest;
import dev.sorokin.eventcommon.exception.ResourceNotFoundException;
import dev.sorokin.eventmanager.kafka.KafkaEventSender;
import dev.sorokin.eventmanager.mapper.EventEntityMapper;
import dev.sorokin.eventmanager.mapper.LocationEntityMapper;
import dev.sorokin.eventmanager.model.domain.Event;
import dev.sorokin.eventmanager.model.domain.Location;
import dev.sorokin.eventmanager.model.domain.User;
import dev.sorokin.eventmanager.model.entity.EventEntity;
import dev.sorokin.eventmanager.model.entity.LocationEntity;
import dev.sorokin.eventmanager.model.enums.EventStatus;
import dev.sorokin.eventmanager.model.enums.UserRole;
import dev.sorokin.eventmanager.repository.EventRepository;
import dev.sorokin.eventmanager.repository.RegistrationRepository;
import dev.sorokin.eventmanager.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@Slf4j
public class EventService {

    private final EventEntityMapper eventEntityMapper;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final LocationService locationService;
    private final LocationEntityMapper locationEntityMapper;
    private final RegistrationRepository registrationRepository;
    private final KafkaEventSender kafkaEventSender;
    private final CacheManager cacheManager;
    private static final String EVENT_CACHE_NAME = "event";

    public EventService(
            EventEntityMapper eventEntityMapper,
            EventRepository eventRepository,
            UserRepository userRepository,
            LocationService locationService,
            LocationEntityMapper locationEntityMapper,
            RegistrationRepository registrationRepository,
            KafkaEventSender kafkaEventSender,
            CacheManager cacheManager
    ) {
        this.eventEntityMapper = eventEntityMapper;
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.locationService = locationService;
        this.locationEntityMapper = locationEntityMapper;
        this.registrationRepository = registrationRepository;
        this.kafkaEventSender = kafkaEventSender;
        this.cacheManager =  cacheManager;
    }

    public Event createEvent(Event eventToCreate) {
        Location location = locationService.findById(eventToCreate.getLocationId());
        LocationEntity locationEntity = locationEntityMapper.toEntity(location);
        locationEntity.setId(eventToCreate.getLocationId());

        if (locationEntity.getCapacity() < eventToCreate.getMaxPlaces()) {
            throw new IllegalArgumentException("Вместимость локации не позволяет провести мероприятие");
        }

        var eventToSave = eventEntityMapper.toEntity(
                eventToCreate,
                locationEntity,
                userRepository.getReferenceById(eventToCreate.getOwnerId())
        );

        return eventEntityMapper.toDomain(
                eventRepository.save(eventToSave)
        );
    }

    @Cacheable(
            value = "event",
            key = "#eventId"
    )
    public Event findById(Long eventId) {
        var foundEvent = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", eventId));
        return eventEntityMapper.toDomain(foundEvent);
    }

    @CacheEvict(
            value = "event",
            key = "#eventId"
    )
    @Transactional
    public Event updateEvent(Long eventId, Event eventToUpdate, User user) {
        if (user.getRole() == UserRole.USER && !user.getId().equals(eventToUpdate.getOwnerId())) {
            throw new AccessDeniedException("У вас недостаточно прав для выполнения данной операции");
        }
        var updatedEvent = eventRepository.findByIdWithOwner(eventId)
                .orElseThrow(
                        () -> new ResourceNotFoundException("Event", eventId)
                );

        Location location = locationService.findById(eventToUpdate.getLocationId());
        List<ChangeItem> changes = new ArrayList<>();

        if (location.getCapacity() < eventToUpdate.getMaxPlaces()) {
            throw new IllegalArgumentException("Вместимость локации не позволяет провести мероприятие");
        }

        if (!Objects.equals(eventToUpdate.getName(), updatedEvent.getName())) {
            changes.add(new ChangeItem(
                    "name",
                    updatedEvent.getName(),
                    eventToUpdate.getName()
            ));
            updatedEvent.setName(eventToUpdate.getName());
        }

        if (!Objects.equals(updatedEvent.getLocation().getId(), eventToUpdate.getLocationId())) {
            LocationEntity locationEntity = locationEntityMapper.toEntity(location);
            locationEntity.setId(eventToUpdate.getLocationId());
            changes.add(new ChangeItem(
                    "locationId",
                    updatedEvent.getLocation().getId(),
                    eventToUpdate.getLocationId()
            ));
            updatedEvent.setLocation(locationEntity);
        }

        if (!Objects.equals(updatedEvent.getStartAt(), eventToUpdate.getStartAt())) {
            changes.add(new ChangeItem(
                    "date",
                    updatedEvent.getStartAt(),
                    eventToUpdate.getStartAt()
            ));
            updatedEvent.setStartAt(eventToUpdate.getStartAt());
        }

        if (!Objects.equals(updatedEvent.getCostTickets(), eventToUpdate.getCostTickets())) {
            changes.add(new ChangeItem(
                    "cost",
                    updatedEvent.getCostTickets(),
                    eventToUpdate.getCostTickets()
            ));
            updatedEvent.setCostTickets(eventToUpdate.getCostTickets());
        }

        if (!Objects.equals(updatedEvent.getDurationMinutes(), eventToUpdate.getDurationMinutes())) {
            changes.add(new ChangeItem(
                    "duration",
                    updatedEvent.getDurationMinutes(),
                    eventToUpdate.getDurationMinutes()
            ));
            updatedEvent.setDurationMinutes(eventToUpdate.getDurationMinutes());
        }

        if (!Objects.equals(updatedEvent.getMaxPlaces(), eventToUpdate.getMaxPlaces())) {
            changes.add(new ChangeItem(
                    "maxPlaces",
                    updatedEvent.getMaxPlaces(),
                    eventToUpdate.getMaxPlaces()
            ));
            updatedEvent.setMaxPlaces(eventToUpdate.getMaxPlaces());
        }

        if (!changes.isEmpty()) {
            kafkaEventSender.sendEvent(new EventChangeKafkaMessage(
                    UUID.randomUUID(),
                    "EVENT_UPDATED",
                    eventId,
                    LocalDateTime.now(),
                    updatedEvent.getOwner().getId(),
                    user.getId(),
                    updatedEvent.getName(),
                    registrationRepository.findSubscriberIdsByEventId(eventId),
                    changes
            ));
        }

        return eventEntityMapper.toDomain(updatedEvent);
    }

    @CacheEvict(
            value = "event",
            key = "#eventId"
    )
    @Transactional
    public void deleteEvent(Long eventId, User currentUser) {
        var deletedEvent = eventRepository.findById(eventId)
                .orElseThrow(
                        () -> new ResourceNotFoundException("Event", eventId)
                );
        if (currentUser.getRole() == UserRole.USER && !currentUser.getId().equals(deletedEvent.getOwner().getId())) {
            throw new AccessDeniedException("У вас недостаточно прав для выполнения данной операции");
        }
        if (deletedEvent.getStartAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Нельзя удалить мероприятие, которое уже началось");
        }

        List<ChangeItem> changes = List.of(new ChangeItem(
                "status",
                deletedEvent.getStatus(),
                EventStatus.CANCELLED
        ));

        kafkaEventSender.sendEvent(new EventChangeKafkaMessage(
                UUID.randomUUID(),
                "EVENT_DELETED",
                eventId,
                LocalDateTime.now(),
                deletedEvent.getOwner().getId(),
                currentUser.getId(),
                deletedEvent.getName(),
                registrationRepository.findSubscriberIdsByEventId(eventId),
                changes
        ));

        deletedEvent.setStatus(EventStatus.CANCELLED);
    }

    public List<Event> searchAllEventsForUser(
            Long ownerId,
            Integer pageNumber,
            Integer pageSize
    ) {
        Pageable pageable = Pageable
                .ofSize(pageSize)
                .withPage(pageNumber);

        return eventRepository.findEventsByOwnerId(ownerId, pageable)
                .stream()
                .map(eventEntityMapper::toDomain)
                .toList();
    }

    public List<Event> searchAllEventsByFilter(
            EventSearchRequest eventSearchRequest,
            Integer pageNumber,
            Integer pageSize
    ) {
        Pageable pageable = Pageable
                .ofSize(pageSize)
                .withPage(pageNumber);

        return eventRepository.findEventsByFilters(
                        eventSearchRequest.name(),
                        eventSearchRequest.placesMin(),
                        eventSearchRequest.placesMax(),
                        eventSearchRequest.dateStartAfter(),
                        eventSearchRequest.dateStartBefore(),
                        eventSearchRequest.costMin(),
                        eventSearchRequest.costMax(),
                        eventSearchRequest.durationMin(),
                        eventSearchRequest.durationMax(),
                        eventSearchRequest.locationId(),
                        eventSearchRequest.eventStatus(),
                        pageable
                )
                .stream()
                .map(eventEntityMapper::toDomain)
                .toList();
    }

    @Scheduled(cron = "${event.status.cron}")
    @Transactional
    public void updateStatuses() {
        // WAIT_START -> STARTED
        List<EventEntity> eventsToStart = eventRepository.findEventsToStart(EventStatus.WAIT_START, LocalDateTime.now());
        for (EventEntity event : eventsToStart) {
            event.setStatus(EventStatus.STARTED);
            publishStatusChangeEvent(
                    event,
                    EventStatus.WAIT_START,
                    EventStatus.STARTED
            );

            evictEventFromCache(event.getId());
        }

        // STARTED -> FINISHED
        List<EventEntity> eventsToFinish = eventRepository.findEventsToFinish(EventStatus.STARTED.name(), LocalDateTime.now());
        for (EventEntity event : eventsToFinish) {
            event.setStatus(EventStatus.FINISHED);
            publishStatusChangeEvent(
                    event,
                    EventStatus.STARTED,
                    EventStatus.FINISHED
            );

            evictEventFromCache(event.getId());
        }

        log.info("Updated statuses: {} events started, {} events finished", eventsToStart.size(), eventsToFinish.size());
    }

    private void publishStatusChangeEvent(EventEntity event, EventStatus oldStatus, EventStatus newStatus) {
        List<ChangeItem> changes = List.of(
                new ChangeItem("status", oldStatus.name(), newStatus.name())
        );

        kafkaEventSender.sendEvent(new EventChangeKafkaMessage(
                UUID.randomUUID(),
                "EVENT_UPDATED",
                event.getId(),
                LocalDateTime.now(),
                event.getOwner().getId(),
                null,
                event.getName(),
                registrationRepository.findSubscriberIdsByEventId(event.getId()),
                changes
        ));
    }

    private void evictEventFromCache(Long eventId) {
        Cache eventCache = cacheManager.getCache(EVENT_CACHE_NAME);
        if (eventCache != null) {
            try {
                eventCache.evict(eventId);
            } catch (RuntimeException ex) {
                log.error("Failed to evict event cache for id={}: {}", eventId, ex.toString());
            }
        }
    }

}