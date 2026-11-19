package com.pharma.base.importer;

import com.pharma.base.repository.ProductRepository;
import com.pharma.base.service.ProductService;
import com.pharma.common.importer.ImportError;
import com.pharma.common.importer.ImportHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品导入行校验单元测试（不依赖 Spring 上下文）。
 */
class ProductImportHandlerTest {

    private ProductService service;
    private ProductRepository repository;
    private ProductImportHandler handler;

    @BeforeEach
    void setUp() {
        service = mock(ProductService.class);
        repository = mock(ProductRepository.class);
        handler = new ProductImportHandler(service, repository);
    }

    @Test
    void 必填与枚举错误按字段返回() {
        ProductImportRow row = new ProductImportRow();
        row.setCode("P-1");
        row.setRxCategory("BAD");
        row.setStorageCond("HOT");

        List<ImportError> errors = handler.validateRow(row, 5);

        assertThat(errors).extracting(ImportError::getField)
                .containsExactlyInAnyOrder("genericName", "rxCategory", "storageCond");
        assertThat(errors).allMatch(e -> e.getRow() == 5);
    }

    @Test
    void 合法行无错误且业务键已去除空白() {
        ProductImportRow row = new ProductImportRow();
        row.setCode("  P-1  ");
        row.setGenericName("阿莫西林胶囊");
        row.setRxCategory("RX");
        row.setStorageCond("ROOM_TEMP");
        row.setFirstAuditStatus("PENDING");

        assertThat(handler.validateRow(row, 2)).isEmpty();
        assertThat(handler.businessKey(row)).isEqualTo("P-1");
    }

    @Test
    void 已存在时SKIP策略不落库() {
        ProductImportRow row = new ProductImportRow();
        row.setCode("P-1");
        row.setGenericName("阿莫西林胶囊");
        when(repository.existsByCode("P-1")).thenReturn(true);

        ImportHandler.ImportOutcome outcome = handler.apply(row, com.pharma.common.importer.DuplicateStrategy.SKIP);

        assertThat(outcome).isEqualTo(ImportHandler.ImportOutcome.SKIPPED);
        verify(service, never()).create(org.mockito.ArgumentMatchers.any());
    }
}
