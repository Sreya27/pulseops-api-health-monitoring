package dev.pulseops.repository;

import dev.pulseops.dto.project.ProjectSummary;
import dev.pulseops.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    @Query("""
            SELECT
                p.id AS id,
                p.name AS name,
                p.slug AS slug,
                p.description AS description,
                p.createdAt AS createdAt,
                COUNT(m) AS monitorCount
            FROM Project p
            LEFT JOIN p.monitors m
            WHERE p.user.id = :userId
            GROUP BY p.id, p.name, p.slug, p.description, p.createdAt
            """)
    List<ProjectSummary> findSummariesByUserId(@Param("userId") Long userId);

    @Query("""
            SELECT
                p.id AS id,
                p.name AS name,
                p.slug AS slug,
                p.description AS description,
                p.createdAt AS createdAt,
                COUNT(m) AS monitorCount
            FROM Project p
            LEFT JOIN p.monitors m
            WHERE p.id = :id
            AND p.user.id = :userId
            GROUP BY p.id, p.name, p.slug, p.description, p.createdAt
            """)
    Optional<ProjectSummary> findSummaryByIdAndUserId(
            @Param("id") Long id,
            @Param("userId") Long userId);

    List<Project> findByUserId(Long userId);

    Optional<Project> findBySlug(String slug);

    boolean existsBySlug(String slug);

    Optional<Project> findByIdAndUserId(Long id, Long userId);
}
