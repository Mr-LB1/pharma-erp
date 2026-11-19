package com.pharma.base.importer;

import com.pharma.base.entity.Customer;
import com.pharma.base.repository.CustomerRepository;
import com.pharma.base.service.CustomerService;
import com.pharma.common.BusinessException;
import com.pharma.common.enums.CreditLevel;
import com.pharma.common.importer.ImportError;
import com.pharma.common.importer.ImportHandler;
import com.pharma.common.importer.ImportParseUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户导入：name 必填，creditLevel 枚举，creditLimit 金额校验，name 作为重复业务键。
 */
@Component
public class CustomerImportHandler implements ImportHandler<CustomerImportRow> {

    private final CustomerService service;
    private final CustomerRepository repository;

    public CustomerImportHandler(CustomerService service, CustomerRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    @Override
    public String resource() {
        return "customers";
    }

    @Override
    public String objectType() {
        return "customer";
    }

    @Override
    public Class<CustomerImportRow> modelClass() {
        return CustomerImportRow.class;
    }

    @Override
    public List<CustomerImportRow> sampleRows() {
        CustomerImportRow row = new CustomerImportRow();
        row.setName("示例连锁药房");
        row.setLicenseNo("沪BB0000001");
        row.setLicenseExpire("2030-12-31");
        row.setCreditLevel("A");
        row.setCreditLimit("100000.00");
        return List.of(row);
    }

    @Override
    public List<List<String>> instructions() {
        return List.of(
                List.of("客户名称", "是", "重复时按重复策略处理"),
                List.of("许可证号", "否", "药品经营许可证号"),
                List.of("许可证到期日", "否", "格式 yyyy-MM-dd"),
                List.of("资信等级", "否", ImportParseUtils.legalValues(CreditLevel.class)),
                List.of("授信额度", "否", "非负金额，如 100000.00")
        );
    }

    @Override
    public List<ImportError> validateRow(CustomerImportRow row, int rowNumber) {
        List<ImportError> errors = new ArrayList<>();
        ImportParseUtils.required("name", row.getName(), "客户名称", rowNumber, errors);
        ImportParseUtils.checkEnum("creditLevel", row.getCreditLevel(), CreditLevel.class, rowNumber, errors);
        try {
            ImportParseUtils.parseDate(row.getLicenseExpire());
        } catch (IllegalArgumentException e) {
            errors.add(new ImportError(rowNumber, "licenseExpire", e.getMessage()));
        }
        try {
            ImportParseUtils.parseDecimal(row.getCreditLimit());
        } catch (IllegalArgumentException e) {
            errors.add(new ImportError(rowNumber, "creditLimit", e.getMessage()));
        }
        return errors;
    }

    @Override
    public String businessKey(CustomerImportRow row) {
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
    public void create(CustomerImportRow row) {
        service.create(toEntity(row));
    }

    @Override
    public void updateByKey(String key, CustomerImportRow row) {
        Customer existing = repository.findFirstByNameOrderByIdAsc(key)
                .orElseThrow(() -> BusinessException.notFound("客户不存在: " + key));
        service.update(existing.getId(), toEntity(row));
    }

    private Customer toEntity(CustomerImportRow row) {
        Customer customer = new Customer();
        customer.setName(ImportParseUtils.trimToNull(row.getName()));
        customer.setLicenseNo(ImportParseUtils.trimToNull(row.getLicenseNo()));
        customer.setLicenseExpire(ImportParseUtils.parseDate(row.getLicenseExpire()));
        customer.setCreditLevel(ImportParseUtils.trimToNull(row.getCreditLevel()));
        customer.setCreditLimit(ImportParseUtils.parseDecimal(row.getCreditLimit()));
        return customer;
    }
}
