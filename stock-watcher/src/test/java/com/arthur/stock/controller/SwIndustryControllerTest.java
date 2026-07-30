package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.PageResult;
import com.arthur.stock.service.SwIndustryService;
import com.arthur.stock.vo.IndustryMemberVO;
import com.arthur.stock.vo.IndustryRankingVO;
import com.arthur.stock.vo.SwIndustryVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SwIndustryController 参数校验单元测试（板块行情修复验证）。
 * <p>
 * 直接实例化 Controller（无需 Spring MVC 上下文），覆盖：
 * <ul>
 *   <li>{@code list}：level 范围校验（0/4 非法，1/2/3 合法）；</li>
 *   <li>{@code ranking}：tradeDate 格式校验；</li>
 *   <li>{@code members}：industryCode 格式、page、size 边界校验。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SwIndustryControllerTest {

    @Mock
    private SwIndustryService swIndustryService;

    @InjectMocks
    private SwIndustryController controller;

    // ==================== list ====================

    @Test
    void list_level超出范围返回400() {
        ApiResponse<List<SwIndustryVO>> resp = controller.list(0);
        assertThat(resp.getCode()).isEqualTo(400);

        resp = controller.list(4);
        assertThat(resp.getCode()).isEqualTo(400);

        verify(swIndustryService, never()).listByLevel(anyInt());
    }

    @Test
    void list_level合法透传service() {
        when(swIndustryService.listByLevel(1)).thenReturn(List.of(
                SwIndustryVO.builder().industryCode("801010").industryName("农林牧渔").build()));

        ApiResponse<List<SwIndustryVO>> resp = controller.list(1);

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData()).hasSize(1);
        assertThat(resp.getData().get(0).getIndustryCode()).isEqualTo("801010");
        verify(swIndustryService).listByLevel(1);
    }

    // ==================== ranking ====================

    @Test
    void ranking_tradeDate格式错误返回400() {
        ApiResponse<List<IndustryRankingVO>> resp = controller.ranking("2026-07-28");
        assertThat(resp.getCode()).isEqualTo(400);

        verify(swIndustryService, never()).getIndustryRanking(anyString());
    }

    @Test
    void ranking_tradeDate合法透传service() {
        when(swIndustryService.getIndustryRanking("20260728")).thenReturn(List.of());

        ApiResponse<List<IndustryRankingVO>> resp = controller.ranking("20260728");

        assertThat(resp.getCode()).isEqualTo(200);
        verify(swIndustryService).getIndustryRanking("20260728");
    }

    @Test
    void ranking_tradeDate为空透传null() {
        when(swIndustryService.getIndustryRanking(isNull())).thenReturn(List.of());

        ApiResponse<List<IndustryRankingVO>> resp = controller.ranking(null);

        assertThat(resp.getCode()).isEqualTo(200);
        verify(swIndustryService).getIndustryRanking(isNull());
    }

    // ==================== members ====================

    @Test
    void members_industryCode为空返回400() {
        ApiResponse<PageResult<IndustryMemberVO>> resp = controller.members("  ", 1, 20, null, null);
        assertThat(resp.getCode()).isEqualTo(400);
        verify(swIndustryService, never()).getIndustryMembers(anyString(), anyString(), anyInt(), anyInt(), anyString());
    }

    @Test
    void members_industryCode非6位数字返回400() {
        ApiResponse<PageResult<IndustryMemberVO>> resp = controller.members("8010", 1, 20, null, null);
        assertThat(resp.getCode()).isEqualTo(400);

        resp = controller.members("8010101", 1, 20, null, null);
        assertThat(resp.getCode()).isEqualTo(400);

        resp = controller.members("80101A", 1, 20, null, null);
        assertThat(resp.getCode()).isEqualTo(400);
    }

    @Test
    void members_industryCode带SI后缀合法_回归801010SI不被拦截() {
        PageResult<IndustryMemberVO> page = new PageResult<>(List.of(), 0L, 1, 20);
        when(swIndustryService.getIndustryMembers(eq("801010.SI"), isNull(), eq(1), eq(20), isNull()))
                .thenReturn(page);

        ApiResponse<PageResult<IndustryMemberVO>> resp = controller.members("801010.SI", 1, 20, null, null);

        assertThat(resp.getCode()).isEqualTo(200);
        verify(swIndustryService).getIndustryMembers("801010.SI", null, 1, 20, null);
    }

    @Test
    void members_page小于1返回400() {
        ApiResponse<PageResult<IndustryMemberVO>> resp = controller.members("801010", 0, 20, null, null);
        assertThat(resp.getCode()).isEqualTo(400);
    }

    @Test
    void members_size越界返回400() {
        ApiResponse<PageResult<IndustryMemberVO>> resp = controller.members("801010", 1, 0, null, null);
        assertThat(resp.getCode()).isEqualTo(400);

        resp = controller.members("801010", 1, 101, null, null);
        assertThat(resp.getCode()).isEqualTo(400);
    }

    @Test
    void members_参数合法透传service() {
        PageResult<IndustryMemberVO> page = new PageResult<>(List.of(), 0L, 1, 20);
        when(swIndustryService.getIndustryMembers(eq("801010"), isNull(), eq(1), eq(20), isNull()))
                .thenReturn(page);

        ApiResponse<PageResult<IndustryMemberVO>> resp = controller.members("801010", 1, 20, null, null);

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData().getPage()).isEqualTo(1);
        verify(swIndustryService).getIndustryMembers("801010", null, 1, 20, null);
    }
}
