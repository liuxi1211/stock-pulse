package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.PageResult;
import com.arthur.stock.service.SwIndustryService;
import com.arthur.stock.vo.IndustryMemberVO;
import com.arthur.stock.vo.IndustryRankingVO;
import com.arthur.stock.vo.SwIndustryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 申万行业分类查询，供前端行业筛选下拉使用。
 */
@Tag(name = "申万行业", description = "申万行业分类列表查询")
@RestController
@RequestMapping("/api/industry")
@RequiredArgsConstructor
public class SwIndustryController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String TRADE_DATE_REGEX = "\\d{8}";
    private static final String INDUSTRY_CODE_REGEX = "\\d{6}";

    private final SwIndustryService swIndustryService;

    @Operation(summary = "按层级查询行业列表", description = "level=1 返回 28 个申万一级行业，level=2/3 返回对应层级")
    @GetMapping("/list")
    public ApiResponse<List<SwIndustryVO>> list(
            @Parameter(description = "行业层级（1/2/3），默认 1")
            @RequestParam(defaultValue = "1") int level) {
        if (level < 1 || level > 3) {
            return ApiResponse.error(400, "行业层级 level 必须为 1、2 或 3");
        }
        return ApiResponse.success(swIndustryService.listByLevel(level));
    }

    @Operation(summary = "行业排行", description = "返回申万一级28个行业的排行数据（涨跌幅/成交额/领涨股/领跌股）")
    @GetMapping("/ranking")
    public ApiResponse<List<IndustryRankingVO>> ranking(
            @Parameter(description = "交易日 yyyyMMdd，默认最新交易日")
            @RequestParam(required = false) String tradeDate) {
        if (tradeDate != null && !tradeDate.isBlank() && !tradeDate.matches(TRADE_DATE_REGEX)) {
            return ApiResponse.error(400, "tradeDate 格式错误，应为 yyyyMMdd");
        }
        return ApiResponse.success(swIndustryService.getIndustryRanking(tradeDate));
    }

    @Operation(summary = "行业成分股", description = "分页返回指定行业的当前成分股（含最新行情）")
    @GetMapping("/members")
    public ApiResponse<PageResult<IndustryMemberVO>> members(
            @Parameter(description = "行业代码，如 801010", required = true)
            @RequestParam String industryCode,
            @Parameter(description = "页码，默认1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页条数，默认20")
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "搜索关键字（股票代码或名称）")
            @RequestParam(required = false) String keyword) {
        if (industryCode == null || industryCode.isBlank()) {
            return ApiResponse.error(400, "行业代码 industryCode 不能为空");
        }
        if (!industryCode.matches(INDUSTRY_CODE_REGEX)) {
            return ApiResponse.error(400, "行业代码 industryCode 格式错误，应为 6 位数字");
        }
        if (page < 1) {
            return ApiResponse.error(400, "页码 page 必须为正整数");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            return ApiResponse.error(400, "每页条数 size 必须在 1 到 " + MAX_PAGE_SIZE + " 之间");
        }
        return ApiResponse.success(swIndustryService.getIndustryMembers(industryCode, null, page, size, keyword));
    }
}
