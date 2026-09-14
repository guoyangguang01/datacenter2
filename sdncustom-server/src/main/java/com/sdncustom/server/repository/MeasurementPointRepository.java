package com.sdncustom.server.repository;

import com.sdncustom.common.model.MeasurementPoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MeasurementPointRepository extends JpaRepository<MeasurementPoint, String> {

    List<MeasurementPoint> findByBusinessId(String businessId);

    /** 是否有输入测点引用该测点（删除保护） */
    boolean existsByReferencePointId(String referencePointId);
}
