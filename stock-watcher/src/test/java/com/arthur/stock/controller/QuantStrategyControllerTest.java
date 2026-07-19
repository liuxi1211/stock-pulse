package com.arthur.stock.controller;

import com.arthur.stock.config.StrategyTemplateLoader;
import com.arthur.stock.constant.StrategyErrorCodes;
import com.arthur.stock.dto.strategy.StrategyConfigUpdateRequest;
import com.arthur.stock.dto.strategy.StrategyCreateRequest;
import com.arthur.stock.dto.strategy.StrategyDTO;
import com.arthur.stock.dto.strategy.StrategyDiffDTO;
import com.arthur.stock.dto.strategy.StrategyRollbackRequest;
import com.arthur.stock.dto.strategy.StrategyTemplateDTO;
import com.arthur.stock.dto.strategy.StrategyUpdateRequest;
import com.arthur.stock.dto.strategy.StrategyValidationError;
import com.arthur.stock.dto.strategy.StrategyVersionDTO;
import com.arthur.stock.exception.GlobalExceptionHandler;
import com.arthur.stock.exception.StrategyValidationException;
import com.arthur.stock.exception.StrategyVersionConflictException;
import com.arthur.stock.service.StrategyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QuantStrategyController 的 MockMvc 切片测试（spec 004 Task 16 / TR-7.1~TR-7.5）。
 * <p>
 * 与 {@code StrategyServiceImplTest}（纯 Mockito 单元测试，覆盖 Service 层）形成互补：
 * 本测试聚焦 <b>HTTP 层</b>——请求路由、@Valid 校验、{@link ApiResponse} 包装、
 * {@link GlobalExceptionHandler} 异常映射（400 / 409）。
 * <p>
 * <b>切片选择</b>：{@code @WebMvcTest(QuantStrategyController.class)} 只加载目标 Controller，
 * 不启动完整上下文，MockMvc + ObjectMapper 自动配置。Controller 的两个直接依赖
 * （{@link StrategyService} / {@link StrategyTemplateLoader}）用 {@code @MockBean} 替换。
 * <p>
 * <b>异常处理</b>：{@link GlobalExceptionHandler} 是 {@code @RestControllerAdvice}，
 * {@code @WebMvcTest} 默认会扫描此类 advice，本测试额外用 {@link Import} 显式引入，
 * 双保险保证 400/409 映射在切片内可用（即使 Spring Boot 调整默认扫描策略也能稳定通过）。
 * <p>
 * <b>认证说明</b>：{@code AuthInterceptor} 由 {@code WebConfig} 注册，{@code @WebMvcTest}
 * 不加载 WebConfig，故本切片<b>不模拟登录</b>，直接覆盖 Controller 业务逻辑；
 * TR-7.4（未登录 302 重定向）依赖 WebConfig 的完整集成测试，本切片不覆盖，在 checklist 注明。
 */
@WebMvcTest(QuantStrategyController.class)
@Import({GlobalExceptionHandler.class, QuantStrategyControllerTest.TestConfig.class})
class QuantStrategyControllerTest {

    /** 测试用的合法 strategyId（32 位十六进制，符合 @PathVariable [0-9a-fA-F]{32}）。 */
    private static final String STRATEGY_ID = "a".repeat(32);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper om;

    @Autowired
    private StrategyService strategyService;

    @Autowired
    private StrategyTemplateLoader strategyTemplateLoader;

    @TestConfiguration
    static class TestConfig {
        @Bean
        StrategyService strategyService() {
            return Mockito.mock(StrategyService.class);
        }

        @Bean
        StrategyTemplateLoader strategyTemplateLoader() {
            return Mockito.mock(StrategyTemplateLoader.class);
        }
    }

    // ==================== TR-7.1 策略 CRUD 全流程 ====================

    /**
     * TR-7.1-a：POST 新建策略成功 → 200，data 为 DTO，code=200。
     */
    @Test
    void createStrategy_合法请求_应返回200和DTO() throws Exception {
        StrategyCreateRequest req = new StrategyCreateRequest();
        req.setName("双均线");
        req.setCategory("TECHNICAL");

        StrategyDTO dto = buildDto(STRATEGY_ID, "双均线", "VERIFIED", 1);

        when(strategyService.createStrategy(any(StrategyCreateRequest.class))).thenReturn(dto);

        mockMvc.perform(post("/api/strategies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.uuid").value(STRATEGY_ID))
                .andExpect(jsonPath("$.data.status").value("VERIFIED"));

        verify(strategyService).createStrategy(any(StrategyCreateRequest.class));
    }

    /**
     * TR-7.1-b：POST 新建策略 name 为空 → @Valid 触发 400。
     */
    @Test
    void createStrategy_名称为空_应返回400() throws Exception {
        StrategyCreateRequest req = new StrategyCreateRequest();

        mockMvc.perform(post("/api/strategies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    /**
     * TR-7.1-c：GET /{id} 详情 → 200，data.config 含当前版本配置。
     */
    @Test
    void getStrategyDetail_存在_应返回200和config() throws Exception {
        StrategyDTO dto = buildDto(STRATEGY_ID, "双均线", "VERIFIED", 1);
        dto.setConfig("{\"name\":\"双均线\"}");

        when(strategyService.getStrategyDetail(STRATEGY_ID)).thenReturn(dto);

        mockMvc.perform(get("/api/strategies/{id}", STRATEGY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.uuid").value(STRATEGY_ID))
                .andExpect(jsonPath("$.data.currentVersion").value(1))
                .andExpect(jsonPath("$.data.config").exists());

        verify(strategyService).getStrategyDetail(STRATEGY_ID);
    }

    /**
     * TR-7.1-d：非法 id（非 32 位十六进制）不匹配路由正则 → 404（路径不匹配，不进入 Controller）。
     */
    @Test
    void getStrategyDetail_id非法格式_应返回404() throws Exception {
        mockMvc.perform(get("/api/strategies/{id}", "not-a-hex-id"))
                .andExpect(status().isNotFound());
    }

    /**
     * TR-7.1-e：PUT /{id} 更新基本信息 → 200，code=200，message="修改成功"。
     */
    @Test
    void updateStrategyBasic_合法_应返回200() throws Exception {
        StrategyUpdateRequest req = new StrategyUpdateRequest();
        req.setName("新名称");

        doNothing().when(strategyService).updateStrategy(eq(STRATEGY_ID), any(StrategyUpdateRequest.class));

        mockMvc.perform(put("/api/strategies/{id}", STRATEGY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("修改成功"));

        verify(strategyService).updateStrategy(eq(STRATEGY_ID), any(StrategyUpdateRequest.class));
    }

    /**
     * TR-7.1-f：PUT /{id}/config 合法 → 200，data 为新版本 DTO。
     */
    @Test
    void updateStrategyConfig_合法_应返回200和新版本DTO() throws Exception {
        StrategyConfigUpdateRequest req = new StrategyConfigUpdateRequest();
        req.setConfigJson("{\"name\":\"双均线\"}");
        req.setExpectedVersion(1);

        StrategyDTO dto = buildDto(STRATEGY_ID, "双均线", "VERIFIED", 2);

        when(strategyService.updateStrategyConfig(eq(STRATEGY_ID), any(StrategyConfigUpdateRequest.class)))
                .thenReturn(dto);

        mockMvc.perform(put("/api/strategies/{id}/config", STRATEGY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("配置已更新"))
                .andExpect(jsonPath("$.data.currentVersion").value(2));

        verify(strategyService).updateStrategyConfig(eq(STRATEGY_ID), any(StrategyConfigUpdateRequest.class));
    }

    /**
     * TR-7.1-g：PUT /{id}/config configJson 为空 → @Valid 触发 400。
     */
    @Test
    void updateStrategyConfig_configJson为空_应返回400() throws Exception {
        StrategyConfigUpdateRequest req = new StrategyConfigUpdateRequest();

        mockMvc.perform(put("/api/strategies/{id}/config", STRATEGY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    /**
     * TR-7.5：PUT /{id}/config engine 校验失败 → 400，body 含 errors 数组。
     * <p>
     * Service 抛 {@link StrategyValidationException}（携带 errors 列表），
     * {@link GlobalExceptionHandler#handleStrategyValidation} 映射为 400 + errors。
     */
    @Test
    void updateStrategyConfig_engine校验失败_应返回400和errors数组() throws Exception {
        StrategyConfigUpdateRequest req = new StrategyConfigUpdateRequest();
        req.setConfigJson("{\"invalid\":true}");
        req.setExpectedVersion(1);

        List<StrategyValidationError> errors = List.of(
                new StrategyValidationError(
                        "trading_config.signals.buy.conditions[0].left",
                        "INVALID_FACTOR",
                        "未知因子: NOT_A_FACTOR"),
                new StrategyValidationError("screen_config.universe", "EMPTY", "股票池为空"));

        when(strategyService.updateStrategyConfig(eq(STRATEGY_ID), any(StrategyConfigUpdateRequest.class)))
                .thenThrow(new StrategyValidationException(errors));

        mockMvc.perform(put("/api/strategies/{id}/config", STRATEGY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(StrategyErrorCodes.STRATEGY_VALIDATION_FAILED))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0].path")
                        .value("trading_config.signals.buy.conditions[0].left"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_FACTOR"))
                .andExpect(jsonPath("$.errors[1].path").value("screen_config.universe"));

        verify(strategyService).updateStrategyConfig(eq(STRATEGY_ID), any(StrategyConfigUpdateRequest.class));
    }

    /**
     * TR-7.1-h：DELETE /{id} → 200，message="删除成功"。
     */
    @Test
    void deleteStrategy_合法_应返回200() throws Exception {
        doNothing().when(strategyService).deleteStrategy(STRATEGY_ID);

        mockMvc.perform(delete("/api/strategies/{id}", STRATEGY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("删除成功"));

        verify(strategyService).deleteStrategy(STRATEGY_ID);
    }

    // ==================== 版本冲突（边界）====================

    /**
     * 边界：PUT /{id}/config 乐观锁冲突 → 409，data.currentVersion 返回主表当前版本。
     */
    @Test
    void updateStrategyConfig_版本冲突_应返回409和currentVersion() throws Exception {
        StrategyConfigUpdateRequest req = new StrategyConfigUpdateRequest();
        req.setConfigJson("{\"name\":\"双均线\"}");
        req.setExpectedVersion(1);

        when(strategyService.updateStrategyConfig(eq(STRATEGY_ID), any(StrategyConfigUpdateRequest.class)))
                .thenThrow(new StrategyVersionConflictException(3));

        mockMvc.perform(put("/api/strategies/{id}/config", STRATEGY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(StrategyErrorCodes.STRATEGY_VERSION_CONFLICT))
                .andExpect(jsonPath("$.data.currentVersion").value(3));

        verify(strategyService).updateStrategyConfig(eq(STRATEGY_ID), any(StrategyConfigUpdateRequest.class));
    }

    // ==================== TR-7.2 版本 CRUD ====================

    /**
     * TR-7.2-a：GET /{id}/versions 列表 → 200，data 为数组（不含 configJson）。
     */
    @Test
    void listVersions_应返回200和版本列表() throws Exception {
        StrategyVersionDTO v2 = new StrategyVersionDTO();
        v2.setVersionNo(2);
        v2.setChangelog("调整 fast");
        StrategyVersionDTO v1 = new StrategyVersionDTO();
        v1.setVersionNo(1);
        v1.setChangelog("初始版本");

        when(strategyService.listVersions(STRATEGY_ID)).thenReturn(List.of(v2, v1));

        mockMvc.perform(get("/api/strategies/{id}/versions", STRATEGY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].versionNo").value(2))
                .andExpect(jsonPath("$.data[1].versionNo").value(1))
                .andExpect(jsonPath("$.data[0].configJson").doesNotExist());

        verify(strategyService).listVersions(STRATEGY_ID);
    }

    /**
     * TR-7.2-b：GET /{id}/versions/{versionNo} 详情 → 200，data.configJson 存在。
     */
    @Test
    void getVersion_应返回200和configJson() throws Exception {
        StrategyVersionDTO v = new StrategyVersionDTO();
        v.setVersionNo(2);
        v.setConfigJson("{\"name\":\"双均线\"}");
        v.setChangelog("调整 fast");

        when(strategyService.getVersion(STRATEGY_ID, 2)).thenReturn(v);

        mockMvc.perform(get("/api/strategies/{id}/versions/{versionNo}", STRATEGY_ID, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.versionNo").value(2))
                .andExpect(jsonPath("$.data.configJson").exists());

        verify(strategyService).getVersion(STRATEGY_ID, 2);
    }

    /**
     * TR-7.2-c：GET /{id}/versions/diff?from=1&to=2 → 200，data 为 Diff 列表。
     */
    @Test
    void diffVersions_应返回200和diff列表() throws Exception {
        List<StrategyDiffDTO> diffs = List.of(
                new StrategyDiffDTO(
                        "trading_config.position_sizing.target",
                        "modified", 0.95, 0.8));

        when(strategyService.diffVersions(STRATEGY_ID, 1, 2)).thenReturn(diffs);

        mockMvc.perform(get("/api/strategies/{id}/versions/diff", STRATEGY_ID)
                        .param("from", "1")
                        .param("to", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].path")
                        .value("trading_config.position_sizing.target"))
                .andExpect(jsonPath("$.data[0].changeType").value("modified"))
                .andExpect(jsonPath("$.data[0].oldValue").value(0.95))
                .andExpect(jsonPath("$.data[0].newValue").value(0.8));

        verify(strategyService).diffVersions(STRATEGY_ID, 1, 2);
    }

    /**
     * TR-7.2-d：POST /{id}/versions/rollback 回滚 → 200，data 为新版本 DTO。
     */
    @Test
    void rollbackVersion_合法_应返回200和新版本() throws Exception {
        StrategyRollbackRequest req = new StrategyRollbackRequest();
        req.setTargetVersion(1);

        StrategyVersionDTO v3 = new StrategyVersionDTO();
        v3.setVersionNo(3);
        v3.setChangelog("回滚到 v1");

        when(strategyService.rollbackVersion(eq(STRATEGY_ID), any(StrategyRollbackRequest.class)))
                .thenReturn(v3);

        mockMvc.perform(post("/api/strategies/{id}/versions/rollback", STRATEGY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("回滚成功"))
                .andExpect(jsonPath("$.data.versionNo").value(3));

        verify(strategyService).rollbackVersion(eq(STRATEGY_ID), any(StrategyRollbackRequest.class));
    }

    // ==================== TR-7.3 模板接口 ====================

    /**
     * TR-7.3-a：GET /templates 列表 → 200，data 为模板数组。
     */
    @Test
    void listTemplates_应返回200和模板列表() throws Exception {
        StrategyTemplateDTO t = new StrategyTemplateDTO();
        t.setId("dual_ma");
        t.setName("双均线");
        t.setCategory("TECHNICAL");
        t.setScope("single");

        when(strategyTemplateLoader.listTemplates()).thenReturn(List.of(t));

        mockMvc.perform(get("/api/strategies/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").value("dual_ma"))
                .andExpect(jsonPath("$.data[0].name").value("双均线"));

        verify(strategyTemplateLoader).listTemplates();
    }

    /**
     * TR-7.3-b：GET /templates/{templateId} 详情 → 200，data 为模板详情。
     */
    @Test
    void getTemplate_存在_应返回200和详情() throws Exception {
        StrategyTemplateDTO t = new StrategyTemplateDTO();
        t.setId("dual_ma");
        t.setName("双均线");
        t.setConfigJson("{\"name\":\"双均线\"}");

        when(strategyTemplateLoader.getTemplate("dual_ma")).thenReturn(t);

        mockMvc.perform(get("/api/strategies/templates/{templateId}", "dual_ma"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("dual_ma"))
                .andExpect(jsonPath("$.data.configJson").exists());

        verify(strategyTemplateLoader).getTemplate("dual_ma");
    }

    // ==================== 辅助 ====================

    /** 构造一个填好常用字段的 StrategyDTO。 */
    private static StrategyDTO buildDto(String uuid, String name, String status, int currentVersion) {
        StrategyDTO dto = new StrategyDTO();
        dto.setId(1L);
        dto.setUuid(uuid);
        dto.setName(name);
        dto.setCategory("TECHNICAL");
        dto.setScope("single");
        dto.setStatus(status);
        dto.setCurrentVersion(currentVersion);
        return dto;
    }
}
