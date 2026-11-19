package com.pharma.approval.service;

import com.pharma.approval.entity.ApprovalConfig;
import com.pharma.approval.repository.ApprovalConfigRepository;
import com.pharma.common.BusinessException;
import com.pharma.common.DataScope;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 金额分级审批解析（P1-1）：
 * - 按 bizType + 单据金额命中 approval_config，得到层级 L1/L2 与所需审批角色；
 * - 无命中配置时回落 level 1 / APPROVER，保持历史行为；
 * - 审批动作前校验当前用户角色（ADMIN 豁免）。
 */
@Component
public class ApprovalLevelResolver {

    /** 无配置时的兜底层级与角色。 */
    public static final int DEFAULT_LEVEL = 1;
    public static final String DEFAULT_ROLE = "APPROVER";

    private final ApprovalConfigRepository configRepository;

    public ApprovalLevelResolver(ApprovalConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    /** 解析结果：层级、所需角色、节点名（L1/L2）。 */
    public record Level(int level, String approverRole) {
        public String node() {
            return "L" + level;
        }
    }

    public Level resolve(String bizType, BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        List<ApprovalConfig> matches = configRepository.findMatches(bizType, value);
        if (matches.isEmpty()) {
            return new Level(DEFAULT_LEVEL, DEFAULT_ROLE);
        }
        ApprovalConfig config = matches.get(0);
        return new Level(config.getLevel(), config.getApproverRole());
    }

    /** 审批记录节点名：L1 / L2。 */
    public String node(String bizType, BigDecimal amount) {
        return resolve(bizType, amount).node();
    }

    /**
     * 审批角色校验：当前用户须持有命中层级要求的角色（ROLE_&lt;code&gt;），ADMIN 豁免。
     */
    public void requireApproverRole(String bizType, BigDecimal amount) {
        Level level = resolve(bizType, amount);
        if (DataScope.hasAuthority(DataScope.ROLE_ADMIN)) {
            return;
        }
        if (!DataScope.hasAuthority(roleAuthority(level.approverRole()))) {
            throw BusinessException.forbidden("该单需 " + level.approverRole() + " 角色审批（第 "
                    + level.level() + " 级）");
        }
    }

    private String roleAuthority(String role) {
        return role.startsWith("ROLE_") ? role : "ROLE_" + role;
    }
}
