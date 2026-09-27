package com.webhook.platform.api.service;

import com.webhook.platform.api.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.*;
import com.webhook.platform.common.enums.DeliveryStatus;
import com.webhook.platform.api.domain.repository.*;
import com.webhook.platform.api.dto.EventIngestRequest;
import com.webhook.platform.api.dto.DeliveryStatusCounts;
import com.webhook.platform.api.dto.EventResponse;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.webhook.platform.api.exception.ForbiddenException;
import com.webhook.platform.api.exception.NotFoundException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final ProjectRepository projectRepository;
    private final EventIntake eventIntake;
    private final DeliveryRepository deliveryRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final SequenceGeneratorService sequenceGeneratorService;
    private final SchemaValidationGate schemaValidationGate;

    public Page<EventResponse> listEvents(UUID projectId, Pageable pageable) {
        return listEvents(projectId, null, pageable);
    }

    public Page<EventResponse> listEvents(UUID projectId, String eventType, Pageable pageable) {
        UUID organizationId = TenantContext.require();
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found"));
        
        Page<Event> events = (eventType != null && !eventType.isBlank())
                ? eventRepository.findByProjectIdAndEventTypeContainingIgnoreCase(projectId, eventType.trim(), pageable)
                : eventRepository.findByProjectId(projectId, pageable);

        Map<UUID, DeliveryStatusCounts> counts =
                deliveryCountsOf(events.getContent().stream().map(Event::getId).toList());
        return events.map(event -> withDeliveryCounts(mapToResponse(event), counts));
    }

    public EventResponse getEvent(UUID projectId, UUID eventId) {
        UUID organizationId = TenantContext.require();
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found"));
        
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Event not found"));
        if (!event.getProjectId().equals(projectId)) {
            throw new ForbiddenException("Event does not belong to this project");
        }
        
        return withDeliveryCounts(mapToResponse(event), deliveryCountsOf(List.of(eventId)));
    }

    private Map<UUID, DeliveryStatusCounts> deliveryCountsOf(List<UUID> eventIds) {
        Map<UUID, DeliveryStatusCounts> byEvent = new HashMap<>();
        if (eventIds.isEmpty()) {
            return byEvent;
        }
        for (Object[] row : deliveryRepository.countByEventIdsAndStatus(eventIds)) {
            DeliveryStatusCounts counts = byEvent.computeIfAbsent((UUID) row[0], id -> new DeliveryStatusCounts());
            int n = ((Long) row[2]).intValue();
            switch ((DeliveryStatus) row[1]) {
                case PENDING -> counts.setPending(n);
                case PROCESSING -> counts.setProcessing(n);
                case SUCCESS -> counts.setSuccess(n);
                case FAILED -> counts.setFailed(n);
                case DLQ -> counts.setDlq(n);
                case CANCELLED -> counts.setCancelled(n);
            }
        }
        return byEvent;
    }

    private static EventResponse withDeliveryCounts(EventResponse response, Map<UUID, DeliveryStatusCounts> byEvent) {
        DeliveryStatusCounts counts = byEvent.getOrDefault(response.getId(), new DeliveryStatusCounts());
        response.setDeliveryCounts(counts);
        response.setDeliveriesCreated(counts.getPending() + counts.getProcessing() + counts.getSuccess()
                + counts.getFailed() + counts.getDlq() + counts.getCancelled());
        return response;
    }

    @Transactional
    public EventResponse sendTestEvent(UUID projectId, EventIngestRequest request) {
        UUID organizationId = TenantContext.require();
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found"));
        List<String> schemaWarnings =
                schemaValidationGate.check(project, projectId, request.getType(), request.getData());

        Event event = createEvent(projectId, request);
        event = eventRepository.saveAndFlush(event);
        log.debug("Created test event: {} for project: {}", event.getId(), projectId);

        // The real ingest decision; exact type matching made an order.* Subscription look broken.
        EventIntake.Decision decision = eventIntake.decide(event);
        if (decision.dropped()) {
            log.debug("Rule DROP action — no deliveries for test event {}", event.getId());
            return testEventResponse(event, 0, schemaWarnings);
        }

        List<Delivery> deliveriesToSave = decision.deliveries();
        for (Delivery delivery : deliveriesToSave) {
            if (Boolean.TRUE.equals(delivery.getOrderingEnabled())) {
                delivery.setSequenceNumber(sequenceGeneratorService.nextSequence(delivery.getEndpointId()));
            }
        }
        List<Delivery> savedDeliveries = deliveryRepository.saveAll(deliveriesToSave);

        int deliveriesCreated = savedDeliveries.size();
        log.debug("Created {} deliveries for test event: {}", deliveriesCreated, event.getId());
        return testEventResponse(event, deliveriesCreated, schemaWarnings);
    }

    private EventResponse testEventResponse(Event event, int deliveriesCreated, List<String> schemaWarnings) {
        EventResponse response = mapToResponseWithDeliveries(event, deliveriesCreated);
        response.setSchemaWarnings(schemaWarnings.isEmpty() ? null : schemaWarnings);
        return response;
    }

    private Event createEvent(UUID projectId, EventIngestRequest request) {
        try {
            String payload = objectMapper.writeValueAsString(request.getData());
            return Event.builder()
                    .projectId(projectId)
                    .eventType(request.getType())
                    .payload(payload)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize event payload", e);
        }
    }

    private EventResponse mapToResponse(Event event) {
        return EventResponse.builder()
                .id(event.getId())
                .projectId(event.getProjectId())
                .eventType(event.getEventType())
                .payload(event.getDecompressedPayload())
                .createdAt(event.getCreatedAt())
                .build();
    }

    private EventResponse mapToResponseWithDeliveries(Event event, int deliveriesCreated) {
        return EventResponse.builder()
                .id(event.getId())
                .projectId(event.getProjectId())
                .eventType(event.getEventType())
                .payload(event.getDecompressedPayload())
                .createdAt(event.getCreatedAt())
                .deliveriesCreated(deliveriesCreated)
                .build();
    }
}
