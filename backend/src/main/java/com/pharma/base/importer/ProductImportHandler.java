package com.pharma.base.importer;

import com.pharma.base.entity.Product;
import com.pharma.base.repository.ProductRepository;
import com.pharma.base.service.ProductService;
import com.pharma.common.BusinessException;
import com.pharma.common.enums.FirstAuditStatus;
import com.pharma.common.enums.RxCategory;
import com.pharma.common.enums.StorageCond;
import com.pharma.common.importer.ImportError;
import com.pharma.common.importer.ImportHandler;
import com.pharma.common.importer.ImportParseUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 商品导入：code/genericName 必填，枚举校验，code 唯一。
 */
@Component
public class ProductImportHandler implements ImportHandler<ProductImportRow> {

    private final ProductService service;
    private final ProductRepository repository;

    public ProductImportHandler(ProductService service, ProductRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    @Override
    public String resource() {
        return "products";
    }

    @Override
    public String objectType() {
        return "product";
    }

    @Override
    public Class<ProductImportRow> modelClass() {
        return ProductImportRow.class;
    }

    @Override
    public List<ProductImportRow> sampleRows() {
        ProductImportRow row = new ProductImportRow();
        row.setCode("P-DEMO-001");
        row.setGenericName("阿莫西林胶囊");
        row.setTradeName("阿莫仙");
        row.setSpec("0.25g*24粒");
        row.setManufacturer("示例制药有限公司");
        row.setRxCategory("RX");
        row.setStorageCond("ROOM_TEMP");
        row.setFirstAuditStatus("PENDING");
        return List.of(row);
    }

    @Override
    public List<List<String>> instructions() {
        return List.of(
                List.of("商品编码", "是", "唯一标识；已存在时按重复策略 SKIP/UPDATE/ERROR 处理"),
                List.of("通用名", "是", "药品通用名称"),
                List.of("商品名", "否", "商品名/品牌名"),
                List.of("规格", "否", "如 0.25g*24粒"),
                List.of("生产厂家", "否", "生产企业名称"),
                List.of("处方分类", "否", ImportParseUtils.legalValues(RxCategory.class)),
                List.of("存储条件", "否", ImportParseUtils.legalValues(StorageCond.class)),
                List.of("首营状态", "否", ImportParseUtils.legalValues(FirstAuditStatus.class) + "；默认 PENDING")
        );
    }

    @Override
    public List<ImportError> validateRow(ProductImportRow row, int rowNumber) {
        List<ImportError> errors = new ArrayList<>();
        ImportParseUtils.required("code", row.getCode(), "商品编码", rowNumber, errors);
        ImportParseUtils.required("genericName", row.getGenericName(), "通用名", rowNumber, errors);
        ImportParseUtils.checkEnum("rxCategory", row.getRxCategory(), RxCategory.class, rowNumber, errors);
        ImportParseUtils.checkEnum("storageCond", row.getStorageCond(), StorageCond.class, rowNumber, errors);
        ImportParseUtils.checkEnum("firstAuditStatus", row.getFirstAuditStatus(), FirstAuditStatus.class,
                rowNumber, errors);
        return errors;
    }

    @Override
    public String businessKey(ProductImportRow row) {
        return ImportParseUtils.trimToNull(row.getCode());
    }

    @Override
    public String keyField() {
        return "code";
    }

    @Override
    public boolean existsByKey(String key) {
        return repository.existsByCode(key);
    }

    @Override
    public void create(ProductImportRow row) {
        service.create(toEntity(row));
    }

    @Override
    public void updateByKey(String key, ProductImportRow row) {
        Product existing = repository.findByCode(key)
                .orElseThrow(() -> BusinessException.notFound("商品不存在: " + key));
        service.update(existing.getId(), toEntity(row));
    }

    private Product toEntity(ProductImportRow row) {
        Product product = new Product();
        product.setCode(ImportParseUtils.trimToNull(row.getCode()));
        product.setGenericName(ImportParseUtils.trimToNull(row.getGenericName()));
        product.setTradeName(ImportParseUtils.trimToNull(row.getTradeName()));
        product.setSpec(ImportParseUtils.trimToNull(row.getSpec()));
        product.setManufacturer(ImportParseUtils.trimToNull(row.getManufacturer()));
        product.setRxCategory(ImportParseUtils.trimToNull(row.getRxCategory()));
        product.setStorageCond(ImportParseUtils.trimToNull(row.getStorageCond()));
        product.setFirstAuditStatus(ImportParseUtils.trimToNull(row.getFirstAuditStatus()));
        return product;
    }
}
