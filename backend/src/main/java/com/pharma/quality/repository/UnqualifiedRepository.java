package com.pharma.quality.repository;

import com.pharma.quality.dto.UnqualifiedVO;
import com.pharma.quality.entity.Unqualified;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UnqualifiedRepository extends JpaRepository<Unqualified, Long> {

    @Query("""
            SELECT new com.pharma.quality.dto.UnqualifiedVO(
                u.id, u.productId, u.batchNo, u.quantity, u.reason, u.status, u.createdBy, u.createdAt,
                p.genericName, p.tradeName, p.spec,
                u.warehouseId, w.name, u.disposeMethod, u.disposedAt, u.disposedBy)
            FROM Unqualified u, Product p
            LEFT JOIN u.warehouse w
            WHERE p.id = u.productId
            ORDER BY u.id DESC
            """)
    Page<UnqualifiedVO> findAllWithProduct(Pageable pageable);
}
