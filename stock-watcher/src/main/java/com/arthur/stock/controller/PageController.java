package com.arthur.stock.controller;

import com.arthur.stock.service.MarketService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 页面路由控制器，负责渲染各前端页面视图
 */
@Controller
@RequiredArgsConstructor
public class PageController {

    private final MarketService marketService;

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
}
