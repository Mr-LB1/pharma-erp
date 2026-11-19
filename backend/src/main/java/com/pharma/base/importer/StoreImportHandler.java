package com.pharma.base.importer;

import com.pharma.base.entity.Store;
import com.pharma.base.repository.StoreRepository;
import com.pharma.base.repository.WarehouseRepository;
import com.pharma.base.service.StoreService;
import com.pharma.common.BusinessException;
import com.pharma.common.enums.StoreType;
import com.pharma.common.importer.ImportError;
import com.pharma.common.importer.ImportHandler;
import com.pharma.common.importer.ImportParseUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 门店导入：code/name 必填，type 枚举，warehouseId 必填且需存在，code 唯一。
 */
@Component
public class StoreImportHandler implements ImportHandler<StoreImportRow> {

    private final StoreService service;
    private final StoreRepository repository;
    private final WarehouseRepository warehouseRepository;

    public StoreImportHandler(StoreService service, StoreRepository repository,
                              WarehouseRepository warehouseRepository) {
        this.service = service;
        this.repository = repository;
        this.warehouseRepository = warehouseRepository;
    }

    @Override
    public String resource() {
        return "stores";
    }

    @Override
    public String objectType() {
        return "store";
    }

    @Override
    public Class<StoreImportRow> modelClass() {
        return StoreImportRow.class;
    }

    @Override
    public List<StoreImportRow> sampleRows() {
        StoreImportRow row = new StoreImportRow();
        row.setCode("S-DEMO-001");
        row.setName("示例直营门店");
        row.setType("DIRECT");
        row.setWarehouseId("1");
        row.setAddress("上海市示例路 1 号");
        row.setContactName("张三");
        row.setContactPhone("13800000000");
        row.setLicenseNo("沪CC0000001");
        row.setLicenseExpire("2030-12-31");
        return List.of(row);
    }

    @Override
    public List<List<String>> instructions() {
        return List.of(
                List.of("门店编码", "是", "唯一标识；已存在时按重复策略处理"),
                List.of("门店名称", "是", "门店全称"),
                List.of("门店类型", "否", ImportParseUtils.legalValues(StoreType.class) + "；默认 DIRECT"),
                List.of("关联仓库ID", "是", "取自仓库列表的 ID"),
                List.of("地址", "否", "门店地址"),
                List.of("联系人", "否", "门店联系人"),
                List.of("联系电话", "否", "门店联系电话"),
                List.of("许可证号", "否", "药品经营许可证号"),
                List.of("许可证到期日", "否", "格式 yyyy-MM-dd")
        );
    }

    @Override
    public List<ImportError> validateRow(StoreImportRow row, int rowNumber) {
        List<ImportError> errors = new ArrayList<>();
        ImportParseUtils.required("code", row.getCode(), "门店编码", rowNumber, errors);
        ImportParseUtils.required("name", row.getName(), "门店名称", rowNumber, errors);
        ImportParseUtils.checkEnum("type", row.getType(), StoreType.class, rowNumber, errors);
        try {
            Long warehouseId = ImportParseUtils.parseLong(row.getWarehouseId());
            if (warehouseId == null) {
                errors.add(new ImportError(rowNumber, "warehouseId", "关联仓库ID不能为空"));
            } else if (!warehouseRepository.existsById(warehouseId)) {
                errors.add(new ImportError(rowNumber, "warehouseId", "关联仓库不存在: " + warehouseId));
            }
        } catch (IllegalArgumentException e) {
            errors.add(new ImportError(rowNumber, "warehouseId", e.getMessage()));
        }
        try {
            ImportParseUtils.parseDate(row.getLicenseExpire());
        } catch (IllegalArgumentException e) {
            errors.add(new ImportError(rowNumber, "licenseExpire", e.getMessage()));
        }
        return errors;
    }

    @Override
    public String businessKey(StoreImportRow row) {
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
    public void create(StoreImportRow row) {
        service.create(toEntity(row));
    }

    @Override
    public void updateByKey(String key, StoreImportRow row) {
        Store existing = repository.findByCode(key)
                .orElseThrow(() -> BusinessException.notFound("门店不存在: " + key));
        service.update(existing.getId(), toEntity(row));
    }

    private Store toEntity(StoreImportRow row) {
        Store store = new Store();
        store.setCode(ImportParseUtils.trimToNull(row.getCode()));
        store.setName(ImportParseUtils.trimToNull(row.getName()));
        store.setType(ImportParseUtils.trimToNull(row.getType()));
        store.setWarehouseId(ImportParseUtils.parseLong(row.getWarehouseId()));
        store.setAddress(ImportParseUtils.trimToNull(row.getAddress()));
        store.setContactName(ImportParseUtils.trimToNull(row.getContactName()));
        store.setContactPhone(ImportParseUtils.trimToNull(row.getContactPhone()));
        store.setLicenseNo(ImportParseUtils.trimToNull(row.getLicenseNo()));
        store.setLicenseExpire(ImportParseUtils.parseDate(row.getLicenseExpire()));
        return store;
    }
}
