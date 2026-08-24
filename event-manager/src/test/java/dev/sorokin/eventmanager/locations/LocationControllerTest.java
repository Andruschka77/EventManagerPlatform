package dev.sorokin.eventmanager.locations;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.sorokin.eventmanager.config.TestConfiguration;
import dev.sorokin.eventmanager.dto.request.LocationRequest;
import dev.sorokin.eventmanager.dto.response.LocationResponse;
import dev.sorokin.eventmanager.mapper.LocationDtoMapper;
import dev.sorokin.eventmanager.model.domain.Location;
import dev.sorokin.eventmanager.model.enums.UserRole;
import dev.sorokin.eventmanager.repository.LocationRepository;
import dev.sorokin.eventmanager.service.LocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.http.MediaType;
import java.util.List;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

public class LocationControllerTest extends TestConfiguration {

    @Autowired
    private LocationService locationService;

    @Autowired
    private LocationDtoMapper locationDtoMapper;

    @Autowired
    private LocationRepository locationRepository;

    private final LocationRequest testLocation =
            new LocationRequest(
                null,
                "test-location",
                "Улица Пушкина, дом Колотушкина",
                50000,
                "test-description"
            );

    @BeforeEach
    void cleanup() {
        locationRepository.deleteAll();
    }

    @Test
    public void shouldSuccessCreateLocation() throws Exception {
        String locationJson = objectMapper.writeValueAsString(testLocation);

        String createdLocationJson = mockMvc.perform(post("/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(locationJson)
                .header("Authorization", "Bearer " + userTestConfiguration.getJwtWithRole(UserRole.ADMIN))
        )
                .andExpect(status().is(201))
                .andReturn()
                .getResponse()
                .getContentAsString();

        LocationResponse createdLocationResponse = objectMapper.readValue(createdLocationJson, LocationResponse.class);

        Assertions.assertEquals(testLocation.name(), createdLocationResponse.name());
        Assertions.assertTrue(locationRepository.existsById(createdLocationResponse.id()));
    }

    @Test
    @WithMockUser(username = "admin", authorities = "ADMIN")
    void shouldFailCreateLocationWhenRequestNotValid() throws Exception {
        var notValidLocation = new LocationRequest(
                null,
                null,
                "Улица Пушкина, дом Колотушкина",
                50000,
                "test-description"
        );

        String locationJson = objectMapper.writeValueAsString(notValidLocation);

        mockMvc.perform(post("/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(locationJson)
        )
                .andExpect(status().is(400))
                .andExpect(jsonPath("$.message").value("Некорректный запрос"));
    }

    @Test
    @WithMockUser(username = "admin", authorities = "ADMIN")
    void shouldFailCreateLocationWhenNameAlreadyTaken() throws Exception {
        var firstLocation = locationService.createLocation(locationDtoMapper.toDomain(testLocation));

        var secondLocation = new LocationRequest(
                null,
                firstLocation.getName(),
                firstLocation.getAddress(),
                firstLocation.getCapacity(),
                firstLocation.getDescription()
        );

        String secondLocationJson = objectMapper.writeValueAsString(secondLocation);

        mockMvc.perform(post("/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(secondLocationJson)
        )
                .andExpect(status().is(400))
                .andExpect(jsonPath("$.message").value("Некорректный запрос"))
                .andExpect(jsonPath("$.detailedMessage").value("Location name already taken"));
    }

    @Test
    void shouldSuccessSearchLocationId() throws Exception {
        Location gotLocation = locationService.createLocation(locationDtoMapper.toDomain(testLocation));

        String foundLocationJson = mockMvc.perform(
                get("/locations/{id}", gotLocation.getId())
                        .header("Authorization", "Bearer " + userTestConfiguration.getJwtWithRole(UserRole.ADMIN))
        )
                .andExpect(status().is(200))
                .andReturn()
                .getResponse()
                .getContentAsString();

        LocationResponse foundLocationResponse = objectMapper.readValue(foundLocationJson, LocationResponse.class);

        Assertions.assertEquals(foundLocationResponse.name(), gotLocation.getName());
        Assertions.assertTrue(locationRepository.existsById(foundLocationResponse.id()));
    }

    @Test
    @WithMockUser(username = "user", authorities = "USER")
    void shouldReturnNotFoundWhenLocationNotPresent() throws Exception {
        mockMvc.perform(get("/locations/{id}", Integer.MAX_VALUE))
                .andExpect(status().is(404))
                .andExpect(jsonPath("$.message").value("Сущность не найдена"))
                .andExpect(jsonPath("$.detailedMessage").value("Entity='Location' с ID=%d не найдена".formatted(Integer.MAX_VALUE)));
    }

    @Test
    void shouldSuccessSearchLocations() throws Exception {
        var firstLocation = locationService.createLocation(locationDtoMapper.toDomain(testLocation));

        var secondLocation = locationService.createLocation(
                new Location(
                    null,
                    "test-location-2",
                    firstLocation.getAddress(),
                    firstLocation.getCapacity(),
                    firstLocation.getDescription()
                )
        );

        String foundLocationsJson = mockMvc.perform(get("/locations")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + userTestConfiguration.getJwtWithRole(UserRole.ADMIN))
        )
                .andExpect(status().is(200))
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<LocationResponse> foundLocations = objectMapper.readValue(
                foundLocationsJson,
                new TypeReference<>() {}
        );

        Assertions.assertFalse(foundLocations.isEmpty());
        Assertions.assertTrue(foundLocations.stream()
                .anyMatch(l -> l.name().equals(secondLocation.getName())));
    }

    @Test
    void shouldSuccessUpdateLocationId() throws Exception {
        var firstLocation = locationService.createLocation(locationDtoMapper.toDomain(testLocation));

        var secondLocation = new Location(
                null,
                "test-location-update",
                "Улица Пушкина, дом Колотушкина - update",
                50001,
                "test-description-update"
        );

        String secondLocationJson = objectMapper.writeValueAsString(secondLocation);

        String updatedLocationJson = mockMvc.perform(put("/locations/{id}", firstLocation.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(secondLocationJson)
                .header("Authorization", "Bearer " + userTestConfiguration.getJwtWithRole(UserRole.ADMIN))
        )
                .andExpect(status().is(200))
                .andReturn()
                .getResponse()
                .getContentAsString();

        LocationResponse updatedLocationResponse = objectMapper.readValue(updatedLocationJson, LocationResponse.class);

        Assertions.assertEquals(secondLocation.getName(), updatedLocationResponse.name());
        Assertions.assertNotSame(firstLocation.getName(), updatedLocationResponse.name());
    }

    @Test
    @WithMockUser(username = "user", authorities = "USER")
    void shouldFailUpdateLocationWhenForbidden() throws Exception {
        var firstLocation = locationService.createLocation(locationDtoMapper.toDomain(testLocation));

        var secondLocation = new Location(
                null,
                "test-location-update",
                "Улица Пушкина, дом Колотушкина - update",
                50001,
                "test-description-update"
        );

        String secondLocationJson = objectMapper.writeValueAsString(secondLocation);

        mockMvc.perform(put("/locations/{id}", firstLocation.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondLocationJson)
                )
                .andExpect(status().is(403))
                .andExpect(jsonPath("$.message").value("Forbidden"));
    }

    @Test
    void shouldSuccessDeleteLocationId() throws Exception {
        var deletedLocation = locationService.createLocation(locationDtoMapper.toDomain(testLocation));

        mockMvc.perform(
                delete("/locations/{id}", deletedLocation.getId())
                .header("Authorization", "Bearer " + userTestConfiguration.getJwtWithRole(UserRole.ADMIN))
        )
                .andExpect(status().is(204));
    }

    @Test
    @WithMockUser(username = "user", authorities = "USER")
    void shouldFailDeleteLocationWhenForbidden() throws Exception {
        var deletedLocation = locationService.createLocation(locationDtoMapper.toDomain(testLocation));

        mockMvc.perform(
                delete("/locations/{id}", deletedLocation.getId())
        )
                .andExpect(status().is(403))
                .andExpect(jsonPath("$.message").value("Forbidden"));
    }

    @Test
    @WithMockUser(username = "admin", authorities = "ADMIN")
    void shouldReturnNotFoundWhenDeleteLocationIdNotPresent() throws Exception {
        mockMvc.perform(delete("/locations/{id}", Integer.MAX_VALUE))
                .andExpect(status().is(404))
                .andExpect(jsonPath("$.message").value("Сущность не найдена"))
                .andExpect(jsonPath("$.detailedMessage").value("Entity='Location' с ID=%d не найдена".formatted(Integer.MAX_VALUE)));
    }

}