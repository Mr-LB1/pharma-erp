package com.pharma.finance.repository;

import com.pharma.finance.entity.InvoiceRegister;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvoiceRegisterRepository extends JpaRepository<InvoiceRegister, Long> {

    @Query("select i from InvoiceRegister i where (:keyword is null or i.invoiceNo like %:keyword%) " +
            "and (:bizType is null or i.bizType = :bizType)")
    Page<InvoiceRegister> search(@Param("keyword") String keyword,
                                 @Param("bizType") String bizType,
                                 Pageable pageable);
}
