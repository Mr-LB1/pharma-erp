package com.pharma.system.repository;

import com.pharma.system.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** 按操作 + 对象定位审计行（供关键操作前后值断言/运维排查）。 */
    List<AuditLog> findByActionAndObjectTypeAndObjectIdOrderByIdDesc(String action, String objectType, Long objectId);
}
