package com.sdncustom.server.service;

import com.sdncustom.common.dto.BusinessSystemDTO;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.exception.ResourceNotFoundException;
import com.sdncustom.common.model.BusinessSystem;
import com.sdncustom.server.repository.BusinessSystemRepository;
import com.sdncustom.server.repository.ChannelRepository;
import com.sdncustom.server.repository.MeasurementPointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 业务系统管理：多业务隔离的逻辑维度。
 * 通道/测点创建时校验业务存在；删除业务前要求名下无通道、无测点，防止误删丢数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessSystemService {

    private final BusinessSystemRepository businessRepository;
    private final ChannelRepository channelRepository;
    private final MeasurementPointRepository pointRepository;

    public List<BusinessSystem> findAll() {
        return businessRepository.findAll();
    }

    public BusinessSystem findById(String businessId) {
        return businessRepository.findById(businessId)
                .orElseThrow(() -> new ResourceNotFoundException("BusinessSystem", businessId));
    }

    public boolean exists(String businessId) {
        return businessId != null && businessRepository.existsById(businessId);
    }

    /** 创建通道/测点前调用：业务必填且必须存在 */
    public void requireExists(String businessId) {
        if (businessId == null || businessId.isBlank()) {
            throw new BusinessException(400, "businessId 不能为空");
        }
        if (!businessRepository.existsById(businessId)) {
            throw new BusinessException(400, "业务不存在: " + businessId);
        }
    }

    @Transactional
    public BusinessSystem create(BusinessSystemDTO dto) {
        if (businessRepository.existsById(dto.getBusinessId())) {
            throw new BusinessException(400, "业务已存在: " + dto.getBusinessId());
        }
        BusinessSystem business = new BusinessSystem();
        business.setBusinessId(dto.getBusinessId());
        business.setBusinessName(dto.getBusinessName());
        business.setDescription(dto.getDescription());
        return businessRepository.save(business);
    }

    /** 导入兼容：通道/测点引用的业务不存在时自动创建（名称取 businessId），缺失则落默认业务由调用方兜底 */
    @Transactional
    public void ensureExistsForImport(String businessId) {
        if (businessId == null || businessId.isBlank()) {
            return;
        }
        if (!businessRepository.existsById(businessId)) {
            BusinessSystem business = new BusinessSystem();
            business.setBusinessId(businessId);
            business.setBusinessName(businessId);
            businessRepository.save(business);
            log.info("Import auto-created business: {}", businessId);
        }
    }

    /** 更新仅允许改名称/描述；businessId 不可变更 */
    @Transactional
    public BusinessSystem update(String businessId, BusinessSystemDTO dto) {
        BusinessSystem business = findById(businessId);
        business.setBusinessName(dto.getBusinessName());
        business.setDescription(dto.getDescription());
        return businessRepository.save(business);
    }

    @Transactional
    public void delete(String businessId) {
        findById(businessId);
        int channels = channelRepository.findByBusinessId(businessId).size();
        if (channels > 0) {
            throw new BusinessException(400, "该业务下还有 " + channels + " 个通道，请先删除");
        }
        int points = pointRepository.findByBusinessId(businessId).size();
        if (points > 0) {
            throw new BusinessException(400, "该业务下还有 " + points + " 个测点，请先删除");
        }
        businessRepository.deleteById(businessId);
        log.info("Business deleted: {}", businessId);
    }
}
