package dev.pulseops.repository;

import dev.pulseops.entity.Incident;
import dev.pulseops.entity.enums.IncidentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface IncidentRepository extends JpaRepository<Incident, Long> {
    List<Incident> findByMonitorIdOrderByStartedAtDesc(Long monitorId);

    Optional<Incident> findByMonitorIdAndStatus(Long monitorId, IncidentStatus status);

    @Query("""
            SELECT i FROM Incident i
            JOIN FETCH i.monitor m
            JOIN FETCH m.project
            WHERE m.project.id = :projectId
            ORDER BY i.startedAt DESC
            """)
    List<Incident> findByProjectIdOrderByStartedAtDesc(
            @Param("projectId") Long projectId);

    @Query("""
            SELECT i FROM Incident i
            JOIN FETCH i.monitor m
            JOIN FETCH m.project p
            WHERE p.user.id = :userId
            ORDER BY i.startedAt DESC
            """)
    List<Incident> findByUserIdOrderByStartedAtDesc(
            @Param("userId") Long userId);

    @Query("SELECT COUNT(i) FROM Incident i WHERE i.monitor.project.id = :projectId AND i.status = 'OPEN'")
    Long countOpenByProjectId(Long projectId);
}
