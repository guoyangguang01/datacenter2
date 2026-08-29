package com.sdncustom.server.repository;

import com.sdncustom.common.model.PointSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface PointSourceRepository extends JpaRepository<PointSource, Long> {

    List<PointSource> findByPointId(String pointId);

    List<PointSource> findByChannelId(String channelId);

    List<PointSource> findByPointIdIn(Collection<String> pointIds);

    void deleteByPointId(String pointId);

    void deleteByChannelId(String channelId);
}
