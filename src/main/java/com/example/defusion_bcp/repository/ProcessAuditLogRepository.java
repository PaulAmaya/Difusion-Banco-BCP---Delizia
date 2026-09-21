package com.example.defusion_bcp.repository;

import com.example.defusion_bcp.domain.ProcessAuditLog;
import com.example.defusion_bcp.domain.ProcessStatus;
import com.example.defusion_bcp.domain.ProcessType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessAuditLogRepository extends JpaRepository<ProcessAuditLog, Long> {
    @Query("""
        select event from ProcessAuditLog event
        where (:processType is null or event.processType = :processType)
          and (:status is null or event.status = :status)
          and (:actor is null or lower(event.actor) like lower(concat('%', :actor, '%')))
        order by event.occurredAt desc
        """)
    Page<ProcessAuditLog> search(@Param("processType") ProcessType processType,
                                 @Param("status") ProcessStatus status,
                                 @Param("actor") String actor,
                                 Pageable pageable);
}
