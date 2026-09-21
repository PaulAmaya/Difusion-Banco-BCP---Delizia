package com.example.defusion_bcp.repository;

import com.example.defusion_bcp.domain.PaymentBatch;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentBatchRepository extends JpaRepository<PaymentBatch, Long> {
    @EntityGraph(attributePaths = "recipients")
    List<PaymentBatch> findTop50ByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = "recipients")
    Optional<PaymentBatch> findDetailedById(Long id);
}
