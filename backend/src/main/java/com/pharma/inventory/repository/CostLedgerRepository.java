package com.pharma.inventory.repository;

import com.pharma.inventory.entity.CostLedger;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CostLedgerRepository extends JpaRepository<CostLedger, Long> {

    Optional<CostLedger> findFirstByProductIdOrderByIdDesc(Long productId);
}
