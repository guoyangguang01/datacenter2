package com.sdncustom.server.repository;

import com.sdncustom.common.model.MeasurementPoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MeasurementPointRepository extends JpaRepository<MeasurementPoint, String> {

    List<MeasurementPoint> findByChannelId(String channelId);

    void deleteByChannelId(String channelId);
}
