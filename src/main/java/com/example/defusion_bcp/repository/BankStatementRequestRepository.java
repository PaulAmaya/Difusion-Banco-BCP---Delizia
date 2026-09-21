package com.example.defusion_bcp.repository;

import com.example.defusion_bcp.domain.BankStatementRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BankStatementRequestRepository extends JpaRepository<BankStatementRequest, Long> {
    List<BankStatementRequest> findTop50ByCompanyDbOrderByRequestedAtDesc(String companyDb);
    Optional<BankStatementRequest> findByIdAndCompanyDb(Long id, String companyDb);
}
