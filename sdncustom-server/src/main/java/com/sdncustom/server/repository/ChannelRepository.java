package com.sdncustom.server.repository;

import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.enums.ChannelStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChannelRepository extends JpaRepository<Channel, String> {

    List<Channel> findByAutoConnect(boolean autoConnect);

    List<Channel> findByStatus(ChannelStatus status);

    List<Channel> findByBusinessId(String businessId);

    /** 按业务 + 外部系统代码查通道（code 唯一性校验、外部总表导入定位） */
    List<Channel> findByBusinessIdAndCode(String businessId, String code);
}
