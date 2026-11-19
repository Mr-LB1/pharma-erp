package com.pharma.approval.repository;

import com.pharma.approval.entity.ApprovalRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApprovalRecordRepository extends JpaRepository<ApprovalRecord, Long> {

    List<ApprovalRecord> findByBizTypeAndBizIdOrderByIdDesc(String bizType, Long bizId);

    Optional<ApprovalRecord> findFirstByBizTypeAndBizIdAndStatusOrderByIdDesc(String bizType, Long bizId, String status);

    List<ApprovalRecord> findByBizTypeAndBizIdAndStatus(String bizType, Long bizId, String status);
}
