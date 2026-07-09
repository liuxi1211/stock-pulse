package com.arthur.stock.controller;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import com.arthur.stock.config.StrategyTemplateLoader;
import com.arthur.stock.constant.DisplayableEnum;
import com.arthur.stock.constant.StrategyCategoryEnum;
import com.arthur.stock.constant.StrategyScopeEnum;
import com.arthur.stock.constant.StrategyStatusEnum;
import com.arthur.stock.dto.strategy.StrategyTemplateDTO;
import com.arthur.stock.service.MarketService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 页面路由控制器，负责渲染各前端页面视图
 */
@Controller
@RequiredArgsConstructor
public class PageController {

    private final MarketService marketService;
    private final StrategyTemplateLoader strategyTemplateLoader;

    /**
     * 仪表盘页面，展示大盘指数和自选股行情
     */
    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("pageTitle", "仪表盘");
        model.addAttribute("activeMenu", "dashboard");
        model.addAttribute("indices", marketService.getMarketIndices());
        return "pages/dashboard";
    }

    /**
     * 行情中心页面
     */
    @GetMapping("/page/stock-list")
    public String stockList(Model model) {
        model.addAttribute("pageTitle", "行情中心");
        model.addAttribute("activeMenu", "stock-list");
        return "pages/stock-list";
    }

    /**
     * 自选股页面
     */
    @GetMapping("/page/watchlist")
    public String watchlist(Model model) {
        model.addAttribute("pageTitle", "自选股");
        model.addAttribute("activeMenu", "watchlist");
        return "pages/watchlist";
    }

    /**
     * 系统设置页面
     */
    @GetMapping("/page/settings")
    public String settings(Model model) {
        model.addAttribute("pageTitle", "系统设置");
        model.addAttribute("activeMenu", "settings");
        return "pages/settings";
    }

    /**
     * 因子库页面（标准因子库：元数据浏览 / 试算 / CRUD）
     */
    @GetMapping("/page/factor-library")
    public String factorLibrary(Model model) {
        model.addAttribute("pageTitle", "因子库");
        model.addAttribute("activeMenu", "factor-library");
        return "pages/factor-library";
    }

    /**
     * 选股中心页面（多因子选股：方案管理 + 规则树编辑 + 运行选股 + 结果追踪）
     */
    @GetMapping("/page/screener")
    public String screener(Model model) {
        model.addAttribute("pageTitle", "选股中心");
        model.addAttribute("activeMenu", "screener");
        return "pages/screener";
    }

    /**
     * 用户管理页面（仅管理员可访问）
     */
    @GetMapping("/page/user-management")
    public String userManagement(Model model) {
        model.addAttribute("pageTitle", "用户管理");
        model.addAttribute("activeMenu", "user-management");
        return "pages/user-management";
    }

    // ============================================================
    //  策略管理（004 模块）页面路由
    //  视图目录 quant/strategies/*；数据由前端 JS 调 /api/strategies 拉取，
    //  Controller 仅返回视图 + 注入筛选下拉所需的枚举选项 / 模板列表。
    // ============================================================

    /**
     * 策略列表页（含筛选栏）。
     * 注入分类 / 范围 / 状态三个枚举的 code+label 列表，供筛选下拉渲染。
     */
    @GetMapping("/quant/strategies")
    public String strategyList(Model model) {
        model.addAttribute("pageTitle", "策略管理");
        model.addAttribute("activeMenu", "strategy-list");
        model.addAttribute("categoryOptions", enumOptions(StrategyCategoryEnum.values()));
        model.addAttribute("scopeOptions", enumOptions(StrategyScopeEnum.values()));
        model.addAttribute("statusOptions", enumOptions(StrategyStatusEnum.values()));
        return "quant/strategies/list";
    }

    /**
     * 新建策略编辑器页。注入模板列表（供「从模板创建」下拉）+ 枚举选项。
     */
    @GetMapping("/quant/strategies/new")
    public String strategyNew(Model model) {
        model.addAttribute("pageTitle", "新建策略");
        model.addAttribute("activeMenu", "strategy-list");
        model.addAttribute("strategyId", null);
        model.addAttribute("templates", templateOptions());
        model.addAttribute("categoryOptions", enumOptions(StrategyCategoryEnum.values()));
        model.addAttribute("scopeOptions", enumOptions(StrategyScopeEnum.values()));
        return "quant/strategies/editor";
    }

    /**
     * 编辑已有策略编辑器页。仅注入 strategyId，详情由 JS 调 API 拉取。
     */
    @GetMapping("/quant/strategies/{id}/edit")
    public String strategyEdit(@PathVariable("id") String id, Model model) {
        model.addAttribute("pageTitle", "编辑策略");
        model.addAttribute("activeMenu", "strategy-list");
        model.addAttribute("strategyId", id);
        model.addAttribute("templates", templateOptions());
        model.addAttribute("categoryOptions", enumOptions(StrategyCategoryEnum.values()));
        model.addAttribute("scopeOptions", enumOptions(StrategyScopeEnum.values()));
        return "quant/strategies/editor";
    }

    /**
     * 策略版本时间线页。仅注入 strategyId，版本数据由 JS 调 API 拉取。
     */
    @GetMapping("/quant/strategies/{id}/versions")
    public String strategyVersions(@PathVariable("id") String id, Model model) {
        model.addAttribute("pageTitle", "版本管理");
        model.addAttribute("activeMenu", "strategy-list");
        model.addAttribute("strategyId", id);
        return "quant/strategies/versions";
    }

    // ------------------------------------------------------------
    //  枚举 / 模板下拉选项辅助：转成 [code, label] 二元组列表，
    //  Thymeleaf 侧遍历 o.code / o.label 渲染 <option>。
    // ------------------------------------------------------------

    private static List<String[]> enumOptions(DisplayableEnum[] values) {
        return Arrays.stream(values)
                .sorted(Comparator.comparing(DisplayableEnum::getCode))
                .map(e -> new String[]{e.getCode(), e.getLabel()})
                .toList();
    }

    private List<StrategyTemplateDTO> templateOptions() {
        return strategyTemplateLoader.listTemplates();
    }
}
