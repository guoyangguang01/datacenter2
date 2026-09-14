package com.sdncustom.common.dto;

import com.sdncustom.common.exception.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 列表接口的可选分页包装。
 *
 * 配置类列表（通道/测点/业务）量级由配置决定，默认仍返回全量数组；
 * 只有调用方显式传了 {@code size} 才切页并返回本包装，便于数据量大时按页拉取。
 */
@Data
@AllArgsConstructor
public class PageResult<T> {

    /** 单页最多返回多少条，防止 size 被拉到很大 */
    public static final int MAX_PAGE_SIZE = 1000;

    private List<T> items;
    private long total;
    private int page;
    private int size;

    /**
     * 传了 size 就切页返回 {@link PageResult}，否则原样返回全量列表。
     *
     * @param all  已按业务/通道过滤后的完整列表
     * @param page 页码，从 0 开始；缺省 0
     * @param size 每页条数；null 表示不分页
     */
    public static <T> Object of(List<T> all, Integer page, Integer size) {
        if (size == null) {
            return all;
        }
        int currentPage = page == null ? 0 : page;
        if (currentPage < 0) {
            throw new BusinessException(400, "page 不能为负");
        }
        if (size <= 0 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(400, "size 必须在 1.." + MAX_PAGE_SIZE + " 之间");
        }
        // 用 long 算起点，避免深翻页时 page * size 溢出成负数
        int from = (int) Math.min((long) currentPage * size, all.size());
        int to = (int) Math.min((long) from + size, all.size());
        return new PageResult<>(List.copyOf(all.subList(from, to)), all.size(), currentPage, size);
    }
}
