package com.pharma.finance.controller;

import com.pharma.common.ApiResponse;
import com.pharma.common.PageResult;
import com.pharma.finance.dto.ApInvoiceCreateRequest;
import com.pharma.finance.dto.ApInvoiceVO;
import com.pharma.finance.dto.ArInvoiceCreateRequest;
import com.pharma.finance.dto.ArInvoiceVO;
import com.pharma.finance.dto.InvoiceRegisterCreateRequest;
import com.pharma.finance.dto.InvoiceRegisterVO;
import com.pharma.finance.dto.PaymentCreateRequest;
import com.pharma.finance.dto.PaymentVO;
import com.pharma.finance.dto.ReceiptCreateRequest;
import com.pharma.finance.dto.ReceiptVO;
import com.pharma.finance.dto.StatementVO;
import com.pharma.finance.service.ArApService;
import com.pharma.finance.service.InvoiceRegisterService;
import com.pharma.finance.service.PaymentService;
import com.pharma.finance.service.ReceiptService;
import com.pharma.finance.service.StatementService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * 财务中心：应收/应付、收付款核销、发票登记、客户对账。
 */
@RestController
@RequestMapping("/api/finance")
public class FinanceController {

    private final ArApService arApService;
    private final ReceiptService receiptService;
    private final PaymentService paymentService;
    private final InvoiceRegisterService invoiceRegisterService;
    private final StatementService statementService;

    public FinanceController(ArApService arApService,
                             ReceiptService receiptService,
                             PaymentService paymentService,
                             InvoiceRegisterService invoiceRegisterService,
                             StatementService statementService) {
        this.arApService = arApService;
        this.receiptService = receiptService;
        this.paymentService = paymentService;
        this.invoiceRegisterService = invoiceRegisterService;
        this.statementService = statementService;
    }

    // ============ 应收 ============

    @GetMapping("/ar-invoices")
    @PreAuthorize("hasAuthority('finance:ar:view')")
    public ApiResponse<PageResult<ArInvoiceVO>> listAr(@RequestParam(required = false) Long customerId,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(required = false) String keyword,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(arApService.listAr(customerId, status, keyword, page, size));
    }

    @PostMapping("/ar-invoices")
    @PreAuthorize("hasAuthority('finance:ar:create')")
    public ApiResponse<ArInvoiceVO> createAr(@Valid @RequestBody ArInvoiceCreateRequest request) {
        return ApiResponse.ok(arApService.createManualAr(
                request.customerId(), request.amount(), request.remark()));
    }

    // ============ 应付 ============

    @GetMapping("/ap-invoices")
    @PreAuthorize("hasAuthority('finance:ap:view')")
    public ApiResponse<PageResult<ApInvoiceVO>> listAp(@RequestParam(required = false) Long supplierId,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(required = false) String keyword,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(arApService.listAp(supplierId, status, keyword, page, size));
    }

    @PostMapping("/ap-invoices")
    @PreAuthorize("hasAuthority('finance:ap:create')")
    public ApiResponse<ApInvoiceVO> createAp(@Valid @RequestBody ApInvoiceCreateRequest request) {
        return ApiResponse.ok(arApService.createManualAp(
                request.supplierId(), request.amount(), request.remark()));
    }

    // ============ 收款 ============

    @GetMapping("/receipts")
    @PreAuthorize("hasAuthority('finance:receipt:view')")
    public ApiResponse<PageResult<ReceiptVO>> listReceipts(@RequestParam(required = false) Long customerId,
                                                           @RequestParam(required = false) String keyword,
                                                           @RequestParam(defaultValue = "1") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(receiptService.list(customerId, keyword, page, size));
    }

    @PostMapping("/receipts")
    @PreAuthorize("hasAuthority('finance:receipt:create')")
    public ApiResponse<ReceiptVO> createReceipt(@Valid @RequestBody ReceiptCreateRequest request) {
        return ApiResponse.ok(receiptService.create(request));
    }

    // ============ 付款 ============

    @GetMapping("/payments")
    @PreAuthorize("hasAuthority('finance:payment:view')")
    public ApiResponse<PageResult<PaymentVO>> listPayments(@RequestParam(required = false) Long supplierId,
                                                           @RequestParam(required = false) String keyword,
                                                           @RequestParam(defaultValue = "1") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(paymentService.list(supplierId, keyword, page, size));
    }

    @PostMapping("/payments")
    @PreAuthorize("hasAuthority('finance:payment:create')")
    public ApiResponse<PaymentVO> createPayment(@Valid @RequestBody PaymentCreateRequest request) {
        return ApiResponse.ok(paymentService.create(request));
    }

    // ============ 发票登记 ============

    @GetMapping("/invoice-registers")
    @PreAuthorize("hasAuthority('finance:invoice:view')")
    public ApiResponse<PageResult<InvoiceRegisterVO>> listInvoiceRegisters(@RequestParam(required = false) String keyword,
                                                                           @RequestParam(required = false) String bizType,
                                                                           @RequestParam(defaultValue = "1") int page,
                                                                           @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(invoiceRegisterService.list(keyword, bizType, page, size));
    }

    @PostMapping("/invoice-registers")
    @PreAuthorize("hasAuthority('finance:invoice:create')")
    public ApiResponse<InvoiceRegisterVO> createInvoiceRegister(
            @Valid @RequestBody InvoiceRegisterCreateRequest request) {
        return ApiResponse.ok(invoiceRegisterService.create(request));
    }

    // ============ 对账 ============

    @GetMapping("/statements/ar")
    @PreAuthorize("hasAuthority('finance:statement:view')")
    public ApiResponse<StatementVO> arStatement(@RequestParam Long customerId,
                                                @RequestParam(required = false)
                                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                @RequestParam(required = false)
                                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(statementService.ar(customerId, startDate, endDate));
    }
}
