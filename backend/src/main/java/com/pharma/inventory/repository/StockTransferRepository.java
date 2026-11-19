package com.pharma.inventory.repository;

import com.pharma.inventory.entity.StockTransfer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockTransferRepository extends JpaRepository<StockTransfer, Long> {

    @Query("select t from StockTransfer t where (:keyword is null or t.transferNo like %:keyword%) " +
            "and (:createdBy is null or t.createdBy = :createdBy) " +
            "and (:status is null or :status = '' or t.status = :status)")
    Page<StockTransfer> search(@Param("keyword") String keyword,
                               @Param("createdBy") String createdBy,
                               @Param("status") String status,
                               Pageable pageable);
}
