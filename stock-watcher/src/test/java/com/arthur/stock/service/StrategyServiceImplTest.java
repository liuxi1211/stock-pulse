package com.arthur.stock.service;

import com.arthur.stock.client.StrategyEngineClient;
import com.arthur.stock.constant.StrategyErrorCodes;
import com.arthur.stock.constant.StrategyStatusEnum;
import com.arthur.stock.dto.strategy.StrategyConfigUpdateRequest;
import com.arthur.stock.dto.strategy.StrategyCreateRequest;
import com.arthur.stock.dto.strategy.StrategyDTO;
import com.arthur.stock.dto.strategy.StrategyRollbackRequest;
import com.arthur.stock.dto.strategy.StrategyStatusUpdateRequest;
import com.arthur.stock.dto.strategy.StrategyValidationError;
import com.arthur.stock.dto.strategy.StrategyVersionDTO;
import com.arthur.stock.exception.BusinessException;
import com.arthur.stock.exception.StrategyValidationException;
import com.arthur.stock.exception.StrategyVersionConflictException;
import com.arthur.stock.mapper.QuantStrategyMapper;
import com.arthur.stock.mapper.QuantStrategyVersionMapper;
import com.arthur.stock.model.QuantStrategyDO;
import com.arthur.stock.model.QuantStrategyVersionDO;
import com.arthur.stock.service.impl.StrategyServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StrategyServiceImpl 纯单元测试（spec 004 Task 16 / TR-6.1~TR-6.13）。
 * <p>
 * 沿用 {@link ScreenerServiceImplTest} 的 Mockito 风格：{@code @ExtendWith(MockitoExtension)}
 * + {@code @Mock}/{@code @InjectMocks} + AssertJ，<b>不启动 Spring 上下文、不连真实 DB</b>。
 * <p>
 * <b>TransactionTemplate 处理</b>：StrategyServiceImpl 构造器注入 {@link PlatformTransactionManager}，
 * 内部 {@code new TransactionTemplate(manager)}。由于真实 TransactionTemplate.execute 会回调
 * manager.getTransaction 触发 NPE，这里在 {@link #setUp} 中用反射把 {@code transactionTemplate}
 * 字段替换成包装 {@link NoOpTransactionManager} 的实例——事务回调被<b>原样同步执行</b>（等同 commit），
 * 但不接触任何真实资源，既能验证事务内 mapper 写入顺序，又避免 mock final 类的麻烦。
 */
@ExtendWith(MockitoExtension.class)
class StrategyServiceImplTest {

    @Mock
    private QuantStrategyMapper strategyMapper;
    @Mock
    private QuantStrategyVersionMapper versionMapper;
    @Mock
    private StrategyEngineClient engineClient;
    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private StrategyServiceImpl strategyService;

    @BeforeEach
    void setUp() {
        // 用真实 TransactionTemplate 包装 no-op manager，覆盖 @InjectMocks 注入的实例
        TransactionTemplate noopTemplate = new TransactionTemplate(new NoOpTransactionManager());
        ReflectionTestUtils.setField(strategyService, "transactionTemplate", noopTemplate);
    }

    // ==================== TR-6.1 createStrategy（无 configJson）====================

    /**
     * TR-6.1：createStrategy 无 configJson → status=DRAFT，主表 insert + 版本表 insert(v1, 默认配置)。
     */
    @Test
    void createStrategy_无configJson_应落DRAFT状态和默认配置版本() {
        StrategyCreateRequest req = new StrategyCreateRequest();
        req.setName("双均线");
        req.setDescription("desc");
        req.setCategory("TECHNICAL");
        req.setScope("single");
        req.setTags(List.of("ma", "trend"));

        // mapper.selectOne 在 toDetailDTO 阶段被再次调用以回查
        when(strategyMapper.selectOne(any())).thenAnswer(inv -> {
            QuantStrategyDO s = new QuantStrategyDO();
            s.setId(1L);
            s.setStrategyId("sid");
            s.setName("双均线");
            s.setStatus(StrategyStatusEnum.DRAFT.getCode());
            s.setCurrentVersion(1);
            return s;
        });

        StrategyDTO dto = strategyService.createStrategy(req);

        // 不应调用 engine（无 config）
        verify(engineClient, never()).validate(anyString());

        // 捕获主表 insert 的 DO
        ArgumentCaptor<QuantStrategyDO> mainCaptor = ArgumentCaptor.forClass(QuantStrategyDO.class);
        verify(strategyMapper).insert(mainCaptor.capture());
        QuantStrategyDO insertedMain = mainCaptor.getValue();
        assertThat(insertedMain.getStatus()).isEqualTo(StrategyStatusEnum.DRAFT.getCode());
        assertThat(insertedMain.getCurrentVersion()).isEqualTo(1);
        assertThat(insertedMain.getTags()).isEqualTo("ma,trend");

        // 捕获版本表 insert 的 DO
        ArgumentCaptor<QuantStrategyVersionDO> verCaptor = ArgumentCaptor.forClass(QuantStrategyVersionDO.class);
        verify(versionMapper).insert(verCaptor.capture());
        QuantStrategyVersionDO insertedVer = verCaptor.getValue();
        assertThat(insertedVer.getVersionNo()).isEqualTo(1);
        // 默认配置应包含统一 Schema 关键字段
        assertThat(insertedVer.getConfigJson()).contains("trading_config").contains("backtest_config");

        assertThat(dto.getStatus()).isEqualTo(StrategyStatusEnum.DRAFT.getCode());
    }

    // ==================== TR-6.1b createStrategy 带合法 configJson ====================

    /**
     * TR-6.1b：createStrategy 带合法 configJson → engine.validate 返回空 → status=VERIFIED。
     */
    @Test
    void createStrategy_带合法configJson_应调engine校验并落VERIFIED() {
        String validConfig = "{\"name\":\"s\",\"trading_config\":{}}";
        StrategyCreateRequest req = new StrategyCreateRequest();
        req.setName("s");
        req.setConfigJson(validConfig);
        req.setChangelog("init");

        when(engineClient.validate(validConfig)).thenReturn(Collections.emptyList());
        when(strategyMapper.selectOne(any())).thenAnswer(inv -> {
            QuantStrategyDO s = new QuantStrategyDO();
            s.setId(7L);
            s.setStrategyId("sid");
            s.setName("s");
            s.setStatus(StrategyStatusEnum.VERIFIED.getCode());
            s.setCurrentVersion(1);
            return s;
        });

        StrategyDTO dto = strategyService.createStrategy(req);

        verify(engineClient).validate(validConfig);

        ArgumentCaptor<QuantStrategyDO> mainCaptor = ArgumentCaptor.forClass(QuantStrategyDO.class);
        verify(strategyMapper).insert(mainCaptor.capture());
        assertThat(mainCaptor.getValue().getStatus()).isEqualTo(StrategyStatusEnum.VERIFIED.getCode());

        ArgumentCaptor<QuantStrategyVersionDO> verCaptor = ArgumentCaptor.forClass(QuantStrategyVersionDO.class);
        verify(versionMapper).insert(verCaptor.capture());
        assertThat(verCaptor.getValue().getConfigJson()).isEqualTo(validConfig);

        assertThat(dto.getStatus()).isEqualTo(StrategyStatusEnum.VERIFIED.getCode());
        assertThat(dto.getConfig()).isEqualTo(validConfig);
    }

    // ==================== TR-6.1c createStrategy 带非法 configJson ====================

    /**
     * TR-6.1c：createStrategy 带 configJson 但 engine.validate 返回 errors → 抛 StrategyValidationException，
     * 主表 NEVER insert、版本表 NEVER insert。
     */
    @Test
    void createStrategy_带非法configJson_应抛异常且不落库() {
        String badConfig = "{\"name\":\"s\"}";
        StrategyCreateRequest req = new StrategyCreateRequest();
        req.setName("s");
        req.setConfigJson(badConfig);

        List<StrategyValidationError> errors = List.of(
                new StrategyValidationError("trading_config", "MISSING_FIELD", "缺少 trading_config"));
        when(engineClient.validate(badConfig)).thenReturn(errors);

        assertThatThrownBy(() -> strategyService.createStrategy(req))
                .isInstanceOf(StrategyValidationException.class)
                .extracting("errors").isEqualTo(errors);

        verify(strategyMapper, never()).insert(any());
        verify(versionMapper, never()).insert(any());
    }

    // ==================== TR-6.2 updateStrategyConfig 正常新版本 ====================

    /**
     * TR-6.2：updateStrategyConfig 合法 + 乐观锁匹配 → 写新版本 + current_version 自增 + VERIFIED 保持。
     */
    @Test
    void updateStrategyConfig_合法_应写新版本并自增currentVersion() {
        String oldConfig = "{\"name\":\"s\",\"trading_config\":{}}";
        String newConfig = "{\"name\":\"s2\",\"trading_config\":{}}";

        QuantStrategyDO strategy = buildStrategy(1L, "sid",
                StrategyStatusEnum.VERIFIED.getCode(), 1);
        when(strategyMapper.selectOne(any())).thenReturn(strategy);
        when(engineClient.validate(newConfig)).thenReturn(Collections.emptyList());

        StrategyConfigUpdateRequest req = new StrategyConfigUpdateRequest();
        req.setConfigJson(newConfig);
        req.setExpectedVersion(1);
        req.setChangelog("tweak");

        strategyService.updateStrategyConfig("sid", req);

        verify(engineClient).validate(newConfig);

        ArgumentCaptor<QuantStrategyVersionDO> verCaptor = ArgumentCaptor.forClass(QuantStrategyVersionDO.class);
        verify(versionMapper).insert(verCaptor.capture());
        QuantStrategyVersionDO v = verCaptor.getValue();
        assertThat(v.getVersionNo()).isEqualTo(2);
        assertThat(v.getConfigJson()).isEqualTo(newConfig);
        assertThat(v.getChangelog()).isEqualTo("tweak");

        ArgumentCaptor<QuantStrategyDO> mainCaptor = ArgumentCaptor.forClass(QuantStrategyDO.class);
        verify(strategyMapper).updateById(mainCaptor.capture());
        QuantStrategyDO updated = mainCaptor.getValue();
        assertThat(updated.getCurrentVersion()).isEqualTo(2);
        // VERIFIED 保持，不变 DRAFT
        assertThat(updated.getStatus()).isEqualTo(StrategyStatusEnum.VERIFIED.getCode());
    }

    // ==================== TR-6.3 updateStrategyConfig 非法 config ====================

    /**
     * TR-6.3：updateStrategyConfig engine.validate 返回 errors → 抛异常，版本表 NEVER insert，
     * current_version 不变。
     */
    @Test
    void updateStrategyConfig_非法config_应抛异常且不写版本() {
        String badConfig = "{\"name\":\"s\"}";
        QuantStrategyDO strategy = buildStrategy(1L, "sid",
                StrategyStatusEnum.VERIFIED.getCode(), 3);
        when(strategyMapper.selectOne(any())).thenReturn(strategy);
        when(engineClient.validate(badConfig)).thenReturn(List.of(
                new StrategyValidationError("x", "BAD", "坏")));

        StrategyConfigUpdateRequest req = new StrategyConfigUpdateRequest();
        req.setConfigJson(badConfig);
        req.setExpectedVersion(3);

        assertThatThrownBy(() -> strategyService.updateStrategyConfig("sid", req))
                .isInstanceOf(StrategyValidationException.class);

        verify(versionMapper, never()).insert(any());
        verify(strategyMapper, never()).updateById(any());
    }

    // ==================== TR-6.5 deleteStrategy（软删）====================

    /**
     * TR-6.5：deleteStrategy → updateById status=ARCHIVED。
     */
    @Test
    void deleteStrategy_应软删为ARCHIVED() {
        QuantStrategyDO strategy = buildStrategy(1L, "sid",
                StrategyStatusEnum.ACTIVE.getCode(), 2);
        when(strategyMapper.selectOne(any())).thenReturn(strategy);

        strategyService.deleteStrategy("sid");

        ArgumentCaptor<QuantStrategyDO> captor = ArgumentCaptor.forClass(QuantStrategyDO.class);
        verify(strategyMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(StrategyStatusEnum.ARCHIVED.getCode());
    }

    // ==================== TR-6.6 非法状态转换 ====================

    /**
     * TR-6.6：ARCHIVED → DRAFT 非法 → 抛 BusinessException(STRATEGY_INVALID_STATUS_TRANSITION)。
     */
    @Test
    void updateStatus_ARCHIVED到DRAFT_应抛非法状态转换() {
        QuantStrategyDO strategy = buildStrategy(1L, "sid",
                StrategyStatusEnum.ARCHIVED.getCode(), 1);
        when(strategyMapper.selectOne(any())).thenReturn(strategy);

        StrategyStatusUpdateRequest req = new StrategyStatusUpdateRequest();
        req.setStatus(StrategyStatusEnum.DRAFT.getCode());

        assertThatThrownBy(() -> strategyService.updateStatus("sid", req))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(StrategyErrorCodes.STRATEGY_INVALID_STATUS_TRANSITION);

        verify(strategyMapper, never()).updateById(any());
    }

    /**
     * TR-6.6 正向：VERIFIED → ACTIVE 合法 → updateById status=ACTIVE。
     */
    @Test
    void updateStatus_VERIFIED到ACTIVE_应合法() {
        QuantStrategyDO strategy = buildStrategy(1L, "sid",
                StrategyStatusEnum.VERIFIED.getCode(), 1);
        when(strategyMapper.selectOne(any())).thenReturn(strategy);

        StrategyStatusUpdateRequest req = new StrategyStatusUpdateRequest();
        req.setStatus(StrategyStatusEnum.ACTIVE.getCode());

        strategyService.updateStatus("sid", req);

        ArgumentCaptor<QuantStrategyDO> captor = ArgumentCaptor.forClass(QuantStrategyDO.class);
        verify(strategyMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(StrategyStatusEnum.ACTIVE.getCode());
    }

    // ==================== TR-6.8 rollbackVersion ====================

    /**
     * TR-6.8：rollbackVersion(v1) → 复用 updateStrategyConfig 写新版本（v3），changelog 含"回滚"前缀。
     */
    @Test
    void rollbackVersion_应复用updateConfig写新版本且changelog含回滚() {
        // 主表 current=2
        QuantStrategyDO strategy = buildStrategy(1L, "sid",
                StrategyStatusEnum.VERIFIED.getCode(), 2);
        // 第一次 requireStrategyByStrategyId；versionMapper.selectOne 查到目标版本 v1
        // 第二次（updateStrategyConfig 内部）requireStrategyByStrategyId 再次查主表
        // 第三次（getVersion 内的 requireStrategyByStrategyId）
        when(strategyMapper.selectOne(any())).thenReturn(strategy);

        QuantStrategyVersionDO target = new QuantStrategyVersionDO();
        target.setId(10L);
        target.setStrategyId(1L);
        target.setVersionNo(1);
        target.setConfigJson("{\"name\":\"old\"}");
        target.setChangelog("v1");
        // versionMapper.selectOne 第一次（rollback 查 target）返回 target；
        // 第二次（updateStrategyConfig 内不查 version，跳过）；
        // 第三次（getVersion 查 current=3 的版本）返回新版本
        java.util.Queue<QuantStrategyVersionDO> versionResponses = new java.util.ArrayDeque<>();
        versionResponses.add(target); // rollback 内部查 target
        // getVersion 内部会查 versionNo=3 的版本
        QuantStrategyVersionDO v3 = new QuantStrategyVersionDO();
        v3.setStrategyId(1L);
        v3.setVersionNo(3);
        v3.setConfigJson("{\"name\":\"old\"}");
        v3.setChangelog("回滚到 v1");
        versionResponses.add(v3);
        when(versionMapper.selectOne(any())).thenAnswer(inv -> versionResponses.poll());

        // engine 校验通过
        when(engineClient.validate(anyString())).thenReturn(Collections.emptyList());

        StrategyRollbackRequest req = new StrategyRollbackRequest();
        req.setTargetVersion(1);
        req.setChangelog("手动");

        // 注意：rollback 后 strategy.currentVersion 仍为 2（同一对象引用），
        // getVersion 会用 2 查询。为了让 getVersion 查到 v3，先把 currentVersion 改成 3：
        // 但 updateStrategyConfig 内部已 setCurrentVersion(3)。同一对象引用，下面 strategy 拿到的就是 3。
        StrategyVersionDTO result = strategyService.rollbackVersion("sid", req);

        // engine 被调用（用 target 的 config）
        verify(engineClient).validate("{\"name\":\"old\"}");

        // 新版本 insert，versionNo = 3，changelog 含"回滚到 v1"
        ArgumentCaptor<QuantStrategyVersionDO> verCaptor = ArgumentCaptor.forClass(QuantStrategyVersionDO.class);
        verify(versionMapper).insert(verCaptor.capture());
        QuantStrategyVersionDO inserted = verCaptor.getValue();
        assertThat(inserted.getVersionNo()).isEqualTo(3);
        assertThat(inserted.getConfigJson()).isEqualTo("{\"name\":\"old\"}");
        assertThat(inserted.getChangelog()).contains("回滚到 v1").contains("手动");

        // 主表 current_version 推进到 3
        ArgumentCaptor<QuantStrategyDO> mainCaptor = ArgumentCaptor.forClass(QuantStrategyDO.class);
        verify(strategyMapper).updateById(mainCaptor.capture());
        assertThat(mainCaptor.getValue().getCurrentVersion()).isEqualTo(3);

        assertThat(result).isNotNull();
        assertThat(result.getVersionNo()).isEqualTo(3);
    }

    // ==================== TR-6.11 乐观锁冲突 ====================

    /**
     * TR-6.11：expectedVersion(5) != current_version(2) → 抛 StrategyVersionConflictException，
     * 版本表 NEVER insert、engine NEVER validate。
     */
    @Test
    void updateStrategyConfig_乐观锁不匹配_应抛冲突异常() {
        QuantStrategyDO strategy = buildStrategy(1L, "sid",
                StrategyStatusEnum.VERIFIED.getCode(), 2);
        when(strategyMapper.selectOne(any())).thenReturn(strategy);

        StrategyConfigUpdateRequest req = new StrategyConfigUpdateRequest();
        req.setConfigJson("{\"name\":\"s\"}");
        req.setExpectedVersion(5);

        assertThatThrownBy(() -> strategyService.updateStrategyConfig("sid", req))
                .isInstanceOf(StrategyVersionConflictException.class)
                .extracting("currentVersion").isEqualTo(2);

        verify(engineClient, never()).validate(anyString());
        verify(versionMapper, never()).insert(any());
        verify(strategyMapper, never()).updateById(any());
    }

    // ==================== TR-6.12 DRAFT 自动转 VERIFIED ====================

    /**
     * TR-6.12：updateStrategyConfig 时主表 status=DRAFT，事务后 status 自动变 VERIFIED。
     */
    @Test
    void updateStrategyConfig_DRAFT状态_应在事务后转为VERIFIED() {
        String newConfig = "{\"name\":\"s\",\"trading_config\":{}}";
        QuantStrategyDO strategy = buildStrategy(1L, "sid",
                StrategyStatusEnum.DRAFT.getCode(), 1);
        when(strategyMapper.selectOne(any())).thenReturn(strategy);
        when(engineClient.validate(newConfig)).thenReturn(Collections.emptyList());

        StrategyConfigUpdateRequest req = new StrategyConfigUpdateRequest();
        req.setConfigJson(newConfig);
        req.setExpectedVersion(1);

        strategyService.updateStrategyConfig("sid", req);

        ArgumentCaptor<QuantStrategyDO> captor = ArgumentCaptor.forClass(QuantStrategyDO.class);
        verify(strategyMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(StrategyStatusEnum.VERIFIED.getCode());
        assertThat(captor.getValue().getCurrentVersion()).isEqualTo(2);
    }

    // ==================== getVersion / listVersions / diffVersions 边界 ====================

    /**
     * getVersion 版本不存在 → 抛 STRATEGY_VERSION_NOT_FOUND。
     */
    @Test
    void getVersion_版本不存在_应抛VERSION_NOT_FOUND() {
        QuantStrategyDO strategy = buildStrategy(1L, "sid",
                StrategyStatusEnum.VERIFIED.getCode(), 1);
        when(strategyMapper.selectOne(any())).thenReturn(strategy);
        when(versionMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> strategyService.getVersion("sid", 9))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(StrategyErrorCodes.STRATEGY_VERSION_NOT_FOUND);
    }

    /**
     * getStrategyDetail 策略不存在 → 抛 STRATEGY_NOT_FOUND。
     */
    @Test
    void getStrategyDetail_策略不存在_应抛NOT_FOUND() {
        when(strategyMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> strategyService.getStrategyDetail("missing"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(StrategyErrorCodes.STRATEGY_NOT_FOUND);
    }

    /**
     * diffVersions → 走 JsonDiffUtil 输出（仅断言不抛异常 + size 合理）。
     */
    @Test
    void diffVersions_应返回差异列表() {
        QuantStrategyDO strategy = buildStrategy(1L, "sid",
                StrategyStatusEnum.VERIFIED.getCode(), 2);
        when(strategyMapper.selectOne(any())).thenReturn(strategy);

        String fromJson = "{\"a\":1,\"b\":2}";
        String toJson = "{\"a\":1,\"b\":3,\"c\":4}";
        // loadVersionConfig 调 versionMapper.selectOne 两次：from / to
        java.util.Queue<String> configs = new java.util.ArrayDeque<>();
        configs.add(fromJson);
        configs.add(toJson);
        when(versionMapper.selectOne(any())).thenAnswer(inv -> {
            String cfg = configs.poll();
            QuantStrategyVersionDO v = new QuantStrategyVersionDO();
            v.setConfigJson(cfg);
            return v;
        });

        var diffs = strategyService.diffVersions("sid", 1, 2);
        assertThat(diffs).hasSize(2); // b modified + c added
        assertThat(diffs).anyMatch(d -> "modified".equals(d.getChangeType()) && "b".equals(d.getPath()));
        assertThat(diffs).anyMatch(d -> "added".equals(d.getChangeType()) && "c".equals(d.getPath()));
    }

    // ==================== 辅助 ====================

    private static QuantStrategyDO buildStrategy(Long id, String strategyId, String status, Integer currentVersion) {
        QuantStrategyDO s = new QuantStrategyDO();
        s.setId(id);
        s.setStrategyId(strategyId);
        s.setName("n");
        s.setStatus(status);
        s.setCurrentVersion(currentVersion);
        return s;
    }

    /**
     * no-op PlatformTransactionManager：所有事务操作空实现，让 TransactionTemplate.execute 同步跑回调。
     * <p>
     * 这样事务体内的 mapper.insert/updateById 调用会被真实执行（mock 验证之），但不触碰任何真实资源。
     */
    private static class NoOpTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
            return new org.springframework.transaction.support.SimpleTransactionStatus(true);
        }

        @Override
        public void commit(TransactionStatus status) throws TransactionException {
            // no-op
        }

        @Override
        public void rollback(TransactionStatus status) throws TransactionException {
            // no-op
        }
    }
}
