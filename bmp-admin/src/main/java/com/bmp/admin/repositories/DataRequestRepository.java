package com.bmp.admin.repositories;

import com.bmp.admin.entities.DataRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DataRequestRepository extends JpaRepository<DataRequest, UUID> {

    /** Soonest deadline first — the only sensible order for a statutory clock. */
    List<DataRequest> findByStatusOrderByDueAtAsc(String status);

    List<DataRequest> findAllByOrderByDueAtAsc();

    List<DataRequest> findBySubjectUserId(UUID subjectUserId);

    /** Outstanding work — drives the dashboard tile and the overdue warning. */
    long countByStatusNotIn(List<String> statuses);

    long countByStatusNotInAndDueAtBefore(List<String> statuses, Instant before);
}
