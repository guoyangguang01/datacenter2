package com.sdncustom.server.repository;

import com.sdncustom.common.model.PointSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface PointSourceRepository extends JpaRepository<PointSource, Long> {

    List<PointSource> findByPointId(String pointId);

    List<PointSource> findByChannelId(String channelId);

    List<PointSource> findByPointIdIn(Collection<String> pointIds);

    /** 批量删除（立即执行），避免 replaceBindings 中 INSERT 先于 DELETE 触发唯一约束 */
    @Modifying
    @Query("DELETE FROM PointSource ps WHERE ps.pointId = :pointId")
    void deleteByPointId(@Param("pointId") String pointId);

    @Modifying
    @Query("DELETE FROM PointSource ps WHERE ps.channelId = :channelId")
    void deleteByChannelId(@Param("channelId") String channelId);
}
