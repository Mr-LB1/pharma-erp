package com.pharma.approval.repository;

import com.pharma.approval.entity.ApprovalConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface ApprovalConfigRepository extends JpaRepository<ApprovalConfig, Long> {

    /**
     * 命中金额区间的配置；层级倒序保证区间重叠时取最严格的一档。
     */
    @Query("select c from ApprovalConfig c where c.bizType = :bizType and c.enabled = true " +
            "and c.minAmount <= :amount and c.maxAmount >= :amount order by c.level desc")
    List<ApprovalConfig> findMatches(@Param("bizType") String bizType, @Param("amount") BigDecimal amount);
}
