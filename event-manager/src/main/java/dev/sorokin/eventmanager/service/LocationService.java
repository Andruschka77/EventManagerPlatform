package dev.sorokin.eventmanager.service;

import dev.sorokin.eventcommon.exception.ResourceNotFoundException;
import dev.sorokin.eventmanager.mapper.LocationEntityMapper;
import dev.sorokin.eventmanager.model.domain.Location;
import dev.sorokin.eventmanager.repository.LocationRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class LocationService {

    private final LocationRepository locationRepository;
    private final LocationEntityMapper entityMapper;

    public LocationService(
            LocationRepository locationRepository,
            LocationEntityMapper entityMapper
    ) {
        this.locationRepository = locationRepository;
        this.entityMapper = entityMapper;
    }

    @CacheEvict(
            value = "locationAll",
            allEntries = true
    )
    public Location createLocation(Location locationToCreate) {
        if (locationRepository.existsByName(locationToCreate.getName())) {
            throw new IllegalArgumentException("Location name already taken");
        }

        var locationToSave = entityMapper.toEntity(locationToCreate);

        return entityMapper.toDomain(
                locationRepository.save(locationToSave)
        );
    }

    @Cacheable(
            value = "locationAll",
            key = "#pageNumber + '-' + #pageSize"
    )
    public List<Location> searchLocations(
            Integer pageNumber,
            Integer pageSize
    ) {
        Pageable pageable = Pageable
                .ofSize(pageSize)
                .withPage(pageNumber);

        return locationRepository.findAll(pageable)
                .stream()
                .map(entityMapper::toDomain)
                .toList();
    }

    @Cacheable(
            value = "location",
            key = "#locationId"
    )
    public Location findById(Long locationId) {
        var foundLocation = locationRepository.findById(locationId)
                .orElseThrow(() -> new ResourceNotFoundException("Location", locationId));

        return entityMapper.toDomain(foundLocation);
    }

    @Caching(evict = {
            @CacheEvict(value = "locationAll", allEntries = true),
            @CacheEvict(value = "location", key = "#locationId")
    })
    @Transactional
    public Location updateLocation(
            Long locationId,
            Location locationToUpdate
    ) {
        var updatedLocation = locationRepository.findById(locationId)
                .orElseThrow(
                        () -> new ResourceNotFoundException("Location", locationId)
                );

        updatedLocation.setName(locationToUpdate.getName());
        updatedLocation.setAddress(locationToUpdate.getAddress());
        updatedLocation.setCapacity(locationToUpdate.getCapacity());
        updatedLocation.setDescription(locationToUpdate.getDescription());

        return entityMapper.toDomain(updatedLocation);
    }

    @Caching(evict = {
            @CacheEvict(value = "locationAll", allEntries = true),
            @CacheEvict(value = "location", key = "#locationId")
    })
    public void deleteLocation(Long locationId) {
        if (!locationRepository.existsById(locationId)) {
            throw new ResourceNotFoundException("Location", locationId);
        }

        locationRepository.deleteById(locationId);
    }

}