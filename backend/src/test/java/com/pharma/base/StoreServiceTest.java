package com.pharma.base;

import com.pharma.base.dto.StoreLicenseWarning;
import com.pharma.base.entity.Store;
import com.pharma.base.entity.Warehouse;
import com.pharma.base.event.StoreChangedEvent;
import com.pharma.base.repository.StoreRepository;
import com.pharma.base.repository.WarehouseRepository;
import com.pharma.base.service.StoreService;
import com.pharma.common.BusinessException;
import com.pharma.common.PageResult;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 门店主数据：CRUD 校验、JSONB 配置、变更事件、证照预警。
 */
@SpringBootTest
@Transactional
@RecordApplicationEvents
class StoreServiceTest {

    @Autowired
    private StoreService storeService;
    @Autowired
    private StoreRepository storeRepository;
    @Autowired
    private WarehouseRepository warehouseRepository;
    @Autowired
    private EntityManager entityManager;

    private Long warehouseId;

    @BeforeEach
    void setUp() {
        Warehouse w = new Warehouse();
        w.setName("门店测试仓");
        w.setTempType("ROOM_TEMP");
        w.setStatus("ACTIVE");
        warehouseId = warehouseRepository.save(w).getId();
    }

    @Test
    void 创建门店成功且config以jsonb持久化() {
        Store saved = storeService.create(newStore("ST-001"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();

        entityManager.flush();
        entityManager.clear();

        Store loaded = storeRepository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getCode()).isEqualTo("ST-001");
        assertThat(loaded.getType()).isEqualTo("DIRECT");
        assertThat(loaded.getStatus()).isEqualTo("ACTIVE");
        assertThat(loaded.getLicenseNo()).isEqualTo("LIC-ST-001");
        assertThat(loaded.getConfig()).containsEntry("businessHours", "08:00-22:00");
    }

    @Test
    void 列表与详情返回关联仓库名称() {
        Store saved = storeService.create(newStore("ST-LIST"));

        PageResult<Store> page = storeService.list("ST-LIST", null, 1, 10);
        assertThat(page.getRecords()).hasSize(1);
        assertThat(page.getRecords().get(0).getWarehouseName()).isEqualTo("门店测试仓");
        assertThat(storeService.get(saved.getId()).getWarehouseName()).isEqualTo("门店测试仓");
    }

    @Test
    void 门店编码重复返回400() {
        storeService.create(newStore("ST-DUP"));

        assertThatThrownBy(() -> storeService.create(newStore("ST-DUP")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
    }

    @Test
    void 关联仓库不存在返回400() {
        Store s = newStore("ST-NOWH");
        s.setWarehouseId(99999999L);

        assertThatThrownBy(() -> storeService.create(s))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
    }

    @Test
    void 创建与更新均发布事件(ApplicationEvents events) {
        Store saved = storeService.create(newStore("ST-EVT"));

        Store patch = new Store();
        patch.setName("改名门店");
        storeService.update(saved.getId(), patch);

        List<StoreChangedEvent> published = events.stream(StoreChangedEvent.class).toList();
        assertThat(published).hasSize(2)
                .extracting(StoreChangedEvent::getAction)
                .containsExactly(StoreChangedEvent.Action.CREATE, StoreChangedEvent.Action.UPDATE);
        assertThat(published.get(0).getSnapshot())
                .containsEntry("code", "ST-EVT")
                .containsEntry("warehouseName", "门店测试仓")
                .containsEntry("licenseNo", "LIC-ST-EVT");
    }

    @Test
    void 删除发布事件且使用删除前快照(ApplicationEvents events) {
        Store saved = storeService.create(newStore("ST-DEL"));
        storeService.delete(saved.getId());

        List<StoreChangedEvent> published = events.stream(StoreChangedEvent.class).toList();
        StoreChangedEvent last = published.get(published.size() - 1);
        assertThat(last.getAction()).isEqualTo(StoreChangedEvent.Action.DELETE);
        assertThat(last.getStoreId()).isEqualTo(saved.getId());
        assertThat(last.getCode()).isEqualTo("ST-DEL");
        assertThat(last.getSnapshot()).containsEntry("status", "ACTIVE");
        // P0-4 软删除：记录保留，仅置 INACTIVE（不再物理删除）
        assertThat(storeRepository.findById(saved.getId()))
                .get()
                .extracting(Store::getStatus)
                .isEqualTo("INACTIVE");
    }

    @Test
    void 证照预警含临期门店且排除远期门店() {
        Store near = storeService.create(newStore("ST-NEAR"));
        Store expired = storeService.create(newStore("ST-EXP"));
        expired.setLicenseExpire(LocalDate.now().minusDays(5));
        Store far = storeService.create(newStore("ST-FAR"));
        far.setLicenseExpire(LocalDate.now().plusDays(365));

        List<StoreLicenseWarning> warnings = storeService.expiringLicenses(90);

        assertThat(warnings).anyMatch(w -> w.id().equals(near.getId()) && w.daysLeft() == 30);
        assertThat(warnings).anyMatch(w -> w.id().equals(expired.getId()) && w.daysLeft() < 0);
        assertThat(warnings).noneMatch(w -> w.id().equals(far.getId()));
    }

    private Store newStore(String code) {
        Store s = new Store();
        s.setCode(code);
        s.setName("测试门店-" + code);
        s.setType("DIRECT");
        s.setWarehouseId(warehouseId);
        s.setAddress("测试路 1 号");
        s.setContactName("张三");
        s.setContactPhone("13800000000");
        s.setLicenseNo("LIC-" + code);
        s.setLicenseExpire(LocalDate.now().plusDays(30));
        s.setConfig(Map.of("businessHours", "08:00-22:00", "payment", List.of("CASH", "WECHAT")));
        return s;
    }
}
