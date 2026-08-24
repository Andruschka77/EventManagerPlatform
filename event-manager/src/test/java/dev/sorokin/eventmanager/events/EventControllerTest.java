package dev.sorokin.eventmanager.events;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.sorokin.eventcommon.kafka.EventChangeKafkaMessage;
import dev.sorokin.eventmanager.config.TestConfiguration;
import dev.sorokin.eventmanager.dto.request.EventCreateRequest;
import dev.sorokin.eventmanager.dto.request.EventSearchRequest;
import dev.sorokin.eventmanager.dto.request.EventUpdateRequest;
import dev.sorokin.eventmanager.dto.response.EventResponse;
import dev.sorokin.eventmanager.kafka.KafkaEventSender;
import dev.sorokin.eventmanager.mapper.EventDtoMapper;
import dev.sorokin.eventmanager.model.domain.Event;
import dev.sorokin.eventmanager.model.domain.Location;
import dev.sorokin.eventmanager.model.enums.UserRole;
import dev.sorokin.eventmanager.repository.EventRepository;
import dev.sorokin.eventmanager.service.EventService;
import dev.sorokin.eventmanager.service.LocationService;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.http.MediaType;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class EventControllerTest extends TestConfiguration {

    @Autowired
    private EventService eventService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EventDtoMapper eventDtoMapper;

    @Autowired
    private LocationService locationService;

    @MockBean
    private KafkaEventSender kafkaEventSender;

    private Location testLocation;
    private EventCreateRequest testEvent;
    private String testUserJwt;
    private String testAdminJwt;

    @BeforeEach
    void cleanup() {
        eventRepository.deleteAll();
    }

    @BeforeAll
    void initOnce() {
        testLocation = locationService.createLocation(
                new Location(
                        null,
                        "test-location",
                        "Улица Пушкина, дом Колотушкина",
                        100_000,
                        "test-description"
                )
        );

        testEvent = new EventCreateRequest(
                "test-event",
                50000,
                LocalDateTime.of(2026, Month.OCTOBER, 20, 17, 0, 0),
                1000,
                60,
                testLocation.getId()
        );

        testUserJwt = userTestConfiguration.getJwtWithRole(UserRole.USER);
        testAdminJwt = userTestConfiguration.getJwtWithRole(UserRole.ADMIN);
    }

    @Test
    void shouldSuccessCreateLocation() throws Exception {
        String eventJson = objectMapper.writeValueAsString(testEvent);

        String createdEventJson = mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson)
                        .header("Authorization", "Bearer " + testUserJwt)
                )
                .andExpect(status().is(201))
                .andReturn()
                .getResponse()
                .getContentAsString();

        EventResponse createdEventResponse = objectMapper.readValue(createdEventJson, EventResponse.class);

        Assertions.assertEquals(testEvent.maxPlaces(), createdEventResponse.maxPlaces());
        Assertions.assertTrue(eventRepository.existsById(createdEventResponse.id()));
    }

    @Test
    @WithMockUser(username = "user", authorities = "USER")
    void shouldFailCreateEventWhenRequestNotValid() throws Exception {
        var notValidEvent = new EventCreateRequest(
                null,
                50000,
                LocalDateTime.of(2026, Month.OCTOBER,20,17,0,0),
                1000,
                60,
                testLocation.getId()
        );

        String eventJson = objectMapper.writeValueAsString(notValidEvent);

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson)
                )
                .andExpect(status().is(400))
                .andExpect(jsonPath("$.message").value("Некорректный запрос"));
    }

    @Test
    void shouldSuccessSearchEventId() throws Exception {
        Event gotEvent = eventService.createEvent(eventDtoMapper.toDomainCreate(testEvent, 2L));

        String foundEventJson = mockMvc.perform(
                        get("/events/{id}", gotEvent.getId())
                                .header("Authorization", "Bearer " + testAdminJwt)
                )
                .andExpect(status().is(200))
                .andReturn()
                .getResponse()
                .getContentAsString();

        EventResponse foundEventResponse = objectMapper.readValue(foundEventJson, EventResponse.class);

        Assertions.assertEquals(foundEventResponse.name(), gotEvent.getName());
        Assertions.assertTrue(eventRepository.existsById(foundEventResponse.id()));
    }

    @Test
    @WithMockUser(username = "user", authorities = "USER")
    void shouldReturnNotFoundWhenLocationNotPresent() throws Exception {
        mockMvc.perform(get("/events/{id}", Integer.MAX_VALUE))
                .andExpect(status().is(404))
                .andExpect(jsonPath("$.message").value("Сущность не найдена"))
                .andExpect(jsonPath("$.detailedMessage").value("Entity='Event' с ID=%d не найдена".formatted(Integer.MAX_VALUE)));
    }

    @Test
    void shouldSuccessSearchEvents() throws Exception {
        var firstEvent = eventService.createEvent(
                eventDtoMapper.toDomainCreate(testEvent, userTestConfiguration.getIdFromJwtToken(testUserJwt))
        );

        var secondEvent = eventService.createEvent(
                eventDtoMapper.toDomainCreate(
                        new EventCreateRequest(
                                firstEvent.getName(),
                                50002,
                                LocalDateTime.of(2026, Month.OCTOBER, 20, 19, 0, 0),
                                5000,
                                90,
                                testLocation.getId()
                        ),
                        userTestConfiguration.getIdFromJwtToken(testUserJwt)
                )
        );

        var filter = new EventSearchRequest(
                firstEvent.getName(),
                45000,
                55000,
                LocalDateTime.now(),
                LocalDateTime.of(2026, Month.DECEMBER, 31, 15, 0, 0),
                500,
                7000,
                30,
                120,
                firstEvent.getLocationId(),
                firstEvent.getStatus()
        );

        String filterJson = objectMapper.writeValueAsString(filter);

        String foundEventsJson = mockMvc.perform(post("/events/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(filterJson)
                        .header("Authorization", "Bearer " + testUserJwt)
                )
                .andExpect(status().is(200))
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<EventResponse> foundLocations = objectMapper.readValue(
                foundEventsJson,
                new TypeReference<>() {}
        );

        Assertions.assertFalse(foundLocations.isEmpty());
        Assertions.assertTrue(foundLocations.stream()
                .anyMatch(l -> l.name().equals(secondEvent.getName())));
    }

    @Test
    void shouldSuccessUpdateEventId() throws Exception {
        var firstEvent = eventService.createEvent(
                eventDtoMapper.toDomainCreate(testEvent, userTestConfiguration.getIdFromJwtToken(testUserJwt))
        );

        var secondEvent = new EventUpdateRequest(
                "test-event-update",
                50002,
                LocalDateTime.of(2026, Month.OCTOBER, 20, 19, 0, 0),
                5000,
                90,
                testLocation.getId()
        );

        String secondEventJson = objectMapper.writeValueAsString(secondEvent);

        String updatedEventJson = mockMvc.perform(put("/events/{id}", firstEvent.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondEventJson)
                        .header("Authorization", "Bearer " + testUserJwt)
                )
                .andExpect(status().is(200))
                .andReturn()
                .getResponse()
                .getContentAsString();

        EventResponse updatedEventResponse = objectMapper.readValue(updatedEventJson, EventResponse.class);

        Assertions.assertEquals(secondEvent.maxPlaces(), updatedEventResponse.maxPlaces());
        Assertions.assertNotSame(firstEvent.getName(), updatedEventResponse.name());
    }

    @Test
    void shouldFailUpdateEventWhenForbidden() throws Exception {
        var firstEvent = eventService.createEvent(
                eventDtoMapper.toDomainCreate(testEvent, userTestConfiguration.getIdFromJwtToken(testAdminJwt))
        );

        var secondEvent = new EventUpdateRequest(
                "test-event-update",
                50002,
                LocalDateTime.of(2026, Month.OCTOBER, 20, 19, 0, 0),
                5000,
                90,
                testLocation.getId()
        );

        String secondEventJson = objectMapper.writeValueAsString(secondEvent);

        mockMvc.perform(put("/events/{id}", firstEvent.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondEventJson)
                        .header("Authorization", "Bearer " + testUserJwt)
                )
                .andExpect(status().is(403))
                .andExpect(jsonPath("$.message").value("Forbidden"))
                .andExpect(jsonPath("$.detailedMessage").value("У вас недостаточно прав для выполнения данной операции"));
    }

    @Test
    void shouldSendKafkaMessageOnUpdateEvent() throws Exception {
        var firstEvent = eventService.createEvent(
                eventDtoMapper.toDomainCreate(testEvent, userTestConfiguration.getIdFromJwtToken(testUserJwt))
        );

        var secondEvent = new EventUpdateRequest(
                "test-event-update",
                50002,
                LocalDateTime.of(2026, Month.OCTOBER, 20, 19, 0, 0),
                5000,
                90,
                testLocation.getId()
        );

        mockMvc.perform(put("/events/{id}", firstEvent.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondEvent))
                        .header("Authorization", "Bearer " + testUserJwt)
                )
                .andExpect(status().is(200));

        ArgumentCaptor<EventChangeKafkaMessage> captor = ArgumentCaptor.forClass(EventChangeKafkaMessage.class);
        Mockito.verify(kafkaEventSender, Mockito.times(1)).sendEvent(captor.capture());

        EventChangeKafkaMessage message = captor.getValue();
        Assertions.assertEquals("EVENT_UPDATED", message.eventType());
        Assertions.assertEquals(firstEvent.getId(), message.eventId());
        Assertions.assertTrue(message.changes().stream()
                .anyMatch(c -> "name".equals(c.field())));
    }

    @Test
    void shouldSuccessDeleteEventId() throws Exception {
        var deletedEvent = eventService.createEvent(
                eventDtoMapper.toDomainCreate(testEvent, userTestConfiguration.getIdFromJwtToken(testUserJwt))
        );

        mockMvc.perform(
                        delete("/events/{id}", deletedEvent.getId())
                                .header("Authorization", "Bearer " + testUserJwt)
                )
                .andExpect(status().is(204));
    }

    @Test
    void shouldFailDeleteEventWhenForbidden() throws Exception {
        var deletedEvent = eventService.createEvent(
                eventDtoMapper.toDomainCreate(testEvent, userTestConfiguration.getIdFromJwtToken(testAdminJwt))
        );

        mockMvc.perform(
                        delete("/events/{id}", deletedEvent.getId())
                                .header("Authorization", "Bearer " + testUserJwt)
                )
                .andExpect(status().is(403))
                .andExpect(jsonPath("$.message").value("Forbidden"))
                .andExpect(jsonPath("$.detailedMessage").value("У вас недостаточно прав для выполнения данной операции"));
    }

    @Test
    void shouldFailDeleteEventWhenBadRequest() throws Exception {
        var deletedEvent = eventService.createEvent(
                eventDtoMapper.toDomainCreate(
                        new EventCreateRequest(
                                testEvent.name(),
                                testEvent.maxPlaces(),
                                LocalDateTime.of(2025, Month.OCTOBER, 20, 19, 0, 0),
                                testEvent.cost(),
                                testEvent.duration(),
                                testLocation.getId()
                        ),
                        userTestConfiguration.getIdFromJwtToken(testUserJwt)
                )
        );

        mockMvc.perform(
                        delete("/events/{id}", deletedEvent.getId())
                                .header("Authorization", "Bearer " + testUserJwt)
                )
                .andExpect(status().is(400))
                .andExpect(jsonPath("$.message").value("Некорректный запрос"))
                .andExpect(jsonPath("$.detailedMessage").value("Нельзя удалить мероприятие, которое уже началось"));
    }

    @Test
    void shouldReturnNotFoundWhenDeleteEventIdNotPresent() throws Exception {
        mockMvc.perform(delete("/events/{id}", Integer.MAX_VALUE)
                        .header("Authorization", "Bearer " + testUserJwt)
        )
                .andExpect(status().is(404))
                .andExpect(jsonPath("$.message").value("Сущность не найдена"))
                .andExpect(jsonPath("$.detailedMessage").value("Entity='Event' с ID=%d не найдена".formatted(Integer.MAX_VALUE)));
    }

}