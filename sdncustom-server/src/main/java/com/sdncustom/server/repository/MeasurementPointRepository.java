package com.sdncustom.server.repository;

import com.sdncustom.common.model.MeasurementPoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MeasurementPointRepository extends JpaRepository<MeasurementPoint, String> {
}
