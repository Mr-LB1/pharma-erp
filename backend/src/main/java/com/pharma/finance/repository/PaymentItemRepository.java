package com.pharma.finance.repository;

import com.pharma.finance.entity.PaymentItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentItemRepository extends JpaRepository<PaymentItem, Long> {
    List<PaymentItem> findByPaymentId(Long paymentId);

    List<PaymentItem> findByPaymentIdIn(List<Long> paymentIds);

    List<PaymentItem> findByApInvoiceId(Long apInvoiceId);
}
