package com.pharma.base.repository;

import com.pharma.base.entity.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    boolean existsByName(String name);

    Optional<Customer> findFirstByNameOrderByIdAsc(String name);

    /** 列表状态过滤：默认 ACTIVE；可查 INACTIVE 恢复启用；ALL 查全部 */
    @Query("SELECT c FROM Customer c WHERE (:status = 'ALL' OR c.status = :status) AND " +
            "(:keyword IS NULL OR :keyword = '' OR " +
            "c.name LIKE %:keyword% OR c.licenseNo LIKE %:keyword% OR " +
            "c.creditLevel LIKE %:keyword%)")
    Page<Customer> search(@Param("keyword") String keyword,
                          @Param("status") String status,
                          Pageable pageable);

    Page<Customer> findByFirstAuditStatusAndStatus(String firstAuditStatus, String status, Pageable pageable);
}
