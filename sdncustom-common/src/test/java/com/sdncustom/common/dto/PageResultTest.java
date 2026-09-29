package com.sdncustom.common.dto;

import com.sdncustom.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PageResult 可选分页包装测试")
class PageResultTest {

    private static final List<String> ALL = List.of("a", "b", "c", "d", "e");

    @Test
    @DisplayName("不传 size：原样返回全量列表（不分页）")
    void withoutSizeReturnsAll() {
        Object result = PageResult.of(ALL, null, null);
        assertSame(ALL, result);
    }

    @Test
    @DisplayName("传 size：切页返回 {items,total,page,size}，page 缺省 0")
    void withSizePages() {
        @SuppressWarnings("unchecked")
        PageResult<String> page = (PageResult<String>) PageResult.of(ALL, null, 2);

        assertEquals(List.of("a", "b"), page.getItems());
        assertEquals(5, page.getTotal());
        assertEquals(0, page.getPage());
        assertEquals(2, page.getSize());
    }

    @Test
    @DisplayName("第二页：从 page*size 位置取")
    void secondPage() {
        @SuppressWarnings("unchecked")
        PageResult<String> page = (PageResult<String>) PageResult.of(ALL, 1, 2);

        assertEquals(List.of("c", "d"), page.getItems());
        assertEquals(1, page.getPage());
    }

    @Test
    @DisplayName("末页不足一页：只返回剩余条目，total 仍是全量")
    void partialLastPage() {
        @SuppressWarnings("unchecked")
        PageResult<String> page = (PageResult<String>) PageResult.of(ALL, 2, 2);

        assertEquals(List.of("e"), page.getItems());
        assertEquals(5, page.getTotal());
    }

    @Test
    @DisplayName("翻过头：返回空 items 而不是越界异常，total 不变")
    void pageBeyondEndReturnsEmpty() {
        @SuppressWarnings("unchecked")
        PageResult<String> page = (PageResult<String>) PageResult.of(ALL, 99, 2);

        assertTrue(page.getItems().isEmpty());
        assertEquals(5, page.getTotal());
    }

    @Test
    @DisplayName("超大 page * size 不溢出成负数（用 long 算起点）")
    void hugePageDoesNotOverflow() {
        @SuppressWarnings("unchecked")
        PageResult<String> page = (PageResult<String>) PageResult.of(
                ALL, Integer.MAX_VALUE, PageResult.MAX_PAGE_SIZE);

        assertTrue(page.getItems().isEmpty());
        assertEquals(5, page.getTotal());
    }

    @Test
    @DisplayName("page 为负：400 点名参数")
    void negativePageRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> PageResult.of(ALL, -1, 10));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("page 不能为负"));
    }

    @Test
    @DisplayName("size 越界：0 与 MAX+1 都拒，MAX 本身放行")
    void sizeBounds() {
        BusinessException zero = assertThrows(BusinessException.class,
                () -> PageResult.of(ALL, 0, 0));
        assertEquals(400, zero.getCode());
        assertTrue(zero.getMessage().contains("size 必须在 1.."));

        assertThrows(BusinessException.class,
                () -> PageResult.of(ALL, 0, PageResult.MAX_PAGE_SIZE + 1));

        assertDoesNotThrow(() -> PageResult.of(ALL, 0, PageResult.MAX_PAGE_SIZE));
    }
}
