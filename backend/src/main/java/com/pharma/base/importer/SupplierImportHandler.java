package com.pharma.base.importer;

import com.pharma.base.entity.Supplier;
import com.pharma.base.repository.SupplierRepository;
import com.pharma.base.service.SupplierService;
import com.pharma.common.BusinessException;
import com.pharma.common.enums.SettleType;
import com.pharma.common.importer.ImportError;
import com.pharma.common.importer.ImportHandler;
import com.pharma.common.importer.ImportParseUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 供应商导入：name 必填，settleType 枚举，许可证到期日格式校验，name 作为重复业务键。
 */
@Component
public class SupplierImportHandler implements ImportHandler<SupplierImportRow> {

    private final SupplierService service;
    private final SupplierRepository repository;

    public SupplierImportHandler(SupplierService service, SupplierRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    @Override
    public String resource() {
        return "suppliers";
    }

    @Override
    public String objectType() {
        return "supplier";
    }

    @Override
    public Class<SupplierImportRow> modelClass() {
        return SupplierImportRow.class;
    }

    @Override
    public List<SupplierImportRow> sampleRows() {
        SupplierImportRow row = new SupplierImportRow();
        row.setName("示例医药供应商");
        row.setCreditCode("91310000XXXXXXXXXX");
        row.setLicenseNo("沪AA0000001");
        row.setLicenseExpire("2030-12-31");
        row.setSettleType("MONTHLY");
        return List.of(row);
    }

    @Override
    public List<List<String>> instructions() {
        return List.of(
                List.of("供应商名称", "是", "重复时按重复策略处理"),
                List.of("统一社会信用代码", "否", "18 位统一社会信用代码"),
                List.of("许可证号", "否", "药品经营许可证号"),
                List.of("许可证到期日", "否", "格式 yyyy-MM-dd"),
                List.of("结算方式", "否", ImportParseUtils.legalValues(SettleType.class))
        );
    }

    @Override
    public List<ImportError> validateRow(SupplierImportRow row, int rowNumber) {
        List<ImportError> errors = new ArrayList<>();
        ImportParseUtils.required("name", row.getName(), "供应商名称", rowNumber, errors);
        ImportParseUtils.checkEnum("settleType", row.getSettleType(), SettleType.class, rowNumber, errors);
        try {
            ImportParseUtils.parseDate(row.getLicenseExpire());
        } catch (IllegalArgumentException e) {
            errors.add(new ImportError(rowNumber, "licenseExpire", e.getMessage()));
        }
        return errors;
    }

    @Override
    public String businessKey(SupplierImportRow row) {
        return ImportParseUtils.trimToNull(row.getName());
    }

    @Override
    public String keyField() {
        return "name";
    }

    @Override
    public boolean existsByKey(String key) {
        return repository.existsByName(key);
    }

    @Override
    public void create(SupplierImportRow row) {
        service.create(toEntity(row));
    }

    @Override
    public void updateByKey(String key, SupplierImportRow row) {
        Supplier existing = repository.findFirstByNameOrderByIdAsc(key)
                .orElseThrow(() -> BusinessException.notFound("供应商不存在: " + key));
        service.update(existing.getId(), toEntity(row));
    }

    private Supplier toEntity(SupplierImportRow row) {
        Supplier supplier = new Supplier();
        supplier.setName(ImportParseUtils.trimToNull(row.getName()));
        supplier.setCreditCode(ImportParseUtils.trimToNull(row.getCreditCode()));
        supplier.setLicenseNo(ImportParseUtils.trimToNull(row.getLicenseNo()));
        supplier.setLicenseExpire(ImportParseUtils.parseDate(row.getLicenseExpire()));
        supplier.setSettleType(ImportParseUtils.trimToNull(row.getSettleType()));
        return supplier;
    }
}
