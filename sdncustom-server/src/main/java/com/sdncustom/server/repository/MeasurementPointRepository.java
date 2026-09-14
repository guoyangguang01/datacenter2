package com.sdncustom.server.repository;

import com.sdncustom.common.model.MeasurementPoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface MeasurementPointRepository extends JpaRepository<MeasurementPoint, String> {

    List<MeasurementPoint> findByBusinessId(String businessId);

    /** 引用了该测点的输入测点（删除保护：报错时点名，告诉用户先删哪些点） */
    List<MeasurementPoint> findByReferencePointId(String referencePointId);

    /** 引用了这批输出测点的全部测点（传播用：一次查询命中，避免逐点查） */
    List<MeasurementPoint> findByReferencePointIdIn(Collection<String> referencePointIds);
}
