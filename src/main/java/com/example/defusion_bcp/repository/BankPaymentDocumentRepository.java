package com.example.defusion_bcp.repository;

import com.example.defusion_bcp.domain.BankPaymentDocument;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Set;

public interface BankPaymentDocumentRepository extends JpaRepository<BankPaymentDocument, Long> {
    @Query("select d.docEntry from BankPaymentDocument d where d.companyDb = :company and d.activeKey is not null")
    Set<Long> blockedIds(@Param("company") String company);
}
