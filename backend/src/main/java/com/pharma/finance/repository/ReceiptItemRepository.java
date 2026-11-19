package com.pharma.finance.repository;

import com.pharma.finance.entity.ReceiptItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReceiptItemRepository extends JpaRepository<ReceiptItem, Long> {
    List<ReceiptItem> findByReceiptId(Long receiptId);

    List<ReceiptItem> findByReceiptIdIn(List<Long> receiptIds);

    List<ReceiptItem> findByArInvoiceId(Long arInvoiceId);
}
