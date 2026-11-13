package com.backroom.audit;

import com.backroom.shared.web.PageResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    public record EventView(
            UUID id,
            UUID actorId,
            String actorName,
            String action,
            String resourceType,
            UUID resourceId,
            Instant occurredAt,
            Map<String, String> metadata) {}

    private final AuditRepository events;
    private final Clock clock;

    public AuditService(AuditRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    public void record(
            UUID orgId,
            UUID actorId,
            String actorName,
            String action,
            String resourceType,
            UUID resourceId,
            Map<String, String> metadata) {
        events.save(new AuditEvent(
                orgId,
                actorId,
                actorName,
                action,
                resourceType,
                resourceId,
                new LinkedHashMap<>(metadata),
                clock.instant()));
    }

    public PageResponse<EventView> list(UUID orgId, int page, int size) {
        var result = events.findByOrganizationId(
                orgId, PageResponse.request(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        return PageResponse.from(result.map(event -> new EventView(
                event.getId(),
                event.actorId,
                event.actorName,
                event.action,
                event.resourceType,
                event.resourceId,
                event.getCreatedAt(),
                event.metadata)));
    }
}
