package com.example.defusion_bcp.repository;

import com.example.defusion_bcp.domain.BankPaymentSubmission;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface BankPaymentSubmissionRepository extends JpaRepository<BankPaymentSubmission, Long> {
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from BankPaymentSubmission s where s.id = :id and s.companyDb = :companyDb")
    Optional<BankPaymentSubmission> lockForRelease(Long id, String companyDb);
    @EntityGraph(attributePaths = "documents")
    Optional<BankPaymentSubmission> findByRequestIdAndCompanyDb(String requestId, String companyDb);
    List<BankPaymentSubmission> findTop50ByCompanyDbOrderByCreatedAtDesc(String companyDb);
    @EntityGraph(attributePaths = "documents")
    Optional<BankPaymentSubmission> findByIdAndCompanyDb(Long id, String companyDb);
}
