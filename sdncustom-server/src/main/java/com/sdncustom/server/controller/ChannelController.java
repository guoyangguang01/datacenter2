package com.sdncustom.server.controller;

import com.sdncustom.common.dto.ApiResponse;
import com.sdncustom.common.dto.ChannelDTO;
import com.sdncustom.common.dto.PageResult;
import com.sdncustom.common.exception.BusinessException;
import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.enums.ChannelDirection;
import com.sdncustom.common.model.enums.ProtocolType;
import com.sdncustom.server.service.ChannelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
public class ChannelController {

    private final ChannelService channelService;

    /**
     * 查询通道。默认返回全量数组；显式传 {@code size} 时返回 {@link PageResult} 分页包装。
     */
    @GetMapping
    public ApiResponse<?> findAll(@RequestParam(required = false) String businessId,
                                 @RequestParam(required = false) Integer page,
                                 @RequestParam(required = false) Integer size) {
        List<Channel> channels = businessId != null
                ? channelService.findByBusinessId(businessId)
                : channelService.findAll();
        return ApiResponse.success(PageResult.of(channels, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<Channel> findById(@PathVariable String id) {
        return ApiResponse.success(channelService.findById(id));
    }

    @PostMapping
    public ApiResponse<Channel> create(@Valid @RequestBody ChannelDTO dto) {
        return ApiResponse.success(channelService.create(dto));
    }

    @PutMapping("/{id}")
    public ApiResponse<Channel> update(@PathVariable String id, @Valid @RequestBody ChannelDTO dto) {
        return ApiResponse.success(channelService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, Authentication authentication) {
        log.info("User {} deleting channel {}", authentication.getName(), id);
        channelService.delete(id);
        return ApiResponse.success();
    }

    @PostMapping("/{id}/connect")
    public ApiResponse<Void> connect(@PathVariable String id, Authentication authentication) {
        log.info("User {} connecting channel {}", authentication.getName(), id);
        channelService.connect(id);
        return ApiResponse.success();
    }

    @PostMapping("/{id}/disconnect")
    public ApiResponse<Void> disconnect(@PathVariable String id, Authentication authentication) {
        log.info("User {} disconnecting channel {}", authentication.getName(), id);
        channelService.disconnect(id);
        return ApiResponse.success();
    }

    /**
     * 导入通道配置——只导入通道本身，不含测点（测点数据走 {@code POST /api/data/import}）。
     * 每个通道必填 {@code businessId}；业务不存在时按 businessId 自动创建。
     * body 支持 {@code {"channels": [...]}} 或直接 {@code [...]}。
     * 已存在的通道 upsert；payload 省略 connectionConfig 时保留库中原值。
     */
    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> importConfig(@RequestBody Object body,
                                                        Authentication authentication) {
        log.info("User {} importing channel config", authentication.getName());
        int count = channelService.importConfigs(parseChannels(body));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("channelCount", count);
        return ApiResponse.success(result);
    }

    @SuppressWarnings("unchecked")
    private List<ChannelDTO> parseChannels(Object body) {
        List<Object> items;
        if (body instanceof List<?> list) {
            items = (List<Object>) list;
        } else if (body instanceof Map<?, ?> map) {
            Object channels = ((Map<String, Object>) map).get("channels");
            if (channels == null) {
                throw new BusinessException(400, "缺少 'channels' 数组");
            }
            if (!(channels instanceof List)) {
                throw new BusinessException(400, "'channels' 必须是数组");
            }
            items = (List<Object>) channels;
        } else {
            throw new BusinessException(400, "payload 必须是通道数组，或 {\"channels\": [...]}");
        }

        List<ChannelDTO> dtos = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map)) {
                throw new BusinessException(400, "通道项必须是对象");
            }
            dtos.add(parseChannel((Map<String, Object>) item));
        }
        return dtos;
    }

    private ChannelDTO parseChannel(Map<String, Object> ch) {
        ChannelDTO dto = new ChannelDTO();
        dto.setChannelId(ImportFields.requireString(ch, "channelId"));
        dto.setBusinessId(ImportFields.requireString(ch, "businessId"));
        dto.setChannelName(ImportFields.requireString(ch, "channelName"));
        dto.setProtocolType(ImportFields.parseEnum(ProtocolType.class, ch.get("protocolType"), "protocolType"));
        dto.setDirection(ImportFields.parseEnum(ChannelDirection.class, ch.get("direction"), "direction"));
        dto.setConnectionConfig(ImportFields.optionalString(ch, "connectionConfig"));
        dto.setAutoConnect(ImportFields.optionalBoolean(ch, "autoConnect", false));
        return dto;
    }
}
