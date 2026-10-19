package dev.pulseops.dto.project;

import java.time.Instant;

public interface ProjectSummary {

    Long getId();

    String getName();

    String getSlug();

    String getDescription();

    Instant getCreatedAt();

    long getMonitorCount();
}