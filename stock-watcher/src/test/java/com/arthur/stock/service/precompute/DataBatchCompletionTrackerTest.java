package com.arthur.stock.service.precompute;

import com.arthur.stock.event.DataBatchReadyEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 数据批次完成追踪器测试（spec 026 Phase D3）。
 * <p>
 * 纯 Mockito，mock ApplicationEventPublisher，验证 4 任务聚合 / 去重 / hasError / 超时兜底。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataBatchCompletionTrackerTest {

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private DataBatchCompletionTracker tracker;

    /** spec 指定的 4 个核心任务 taskKey */
    private static final String T1 = "BATCH_1600";
    private static final String T2 = "BATCH_1630_DAILY_BASIC";
    private static final String T3 = "BATCH_1630_MONEYFLOW";
    private static final String T4 = "BATCH_1630_INDEX_DAILY";
    private static final String TRADE_DATE = "20260729";

    @BeforeEach
    void setUp() {
        tracker = new DataBatchCompletionTracker(eventPublisher);
    }

    // ==================== D3.1 ====================

    @Test
    void D3_1_4任务报告后发布事件source为SCHEDULED() {
        tracker.reportCompletion(T1, TRADE_DATE);
        verify(eventPublisher, never()).publishEvent(any());

        tracker.reportCompletion(T2, TRADE_DATE);
        verify(eventPublisher, never()).publishEvent(any());

        tracker.reportCompletion(T3, TRADE_DATE);
        verify(eventPublisher, never()).publishEvent(any());

        tracker.reportCompletion(T4, TRADE_DATE);

        ArgumentCaptor<DataBatchReadyEvent> captor = ArgumentCaptor.forClass(DataBatchReadyEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());

        DataBatchReadyEvent event = captor.getValue();
        assertThat(event.getTradeDate()).isEqualTo(TRADE_DATE);
        assertThat(event.getSource()).isEqualTo("SCHEDULED");
    }

    // ==================== D3.2 ====================

    @Test
    void D3_2_重复报告同taskKey去重() {
        // 同 taskKey 调用 2 次
        tracker.reportCompletion(T1, TRADE_DATE);
        tracker.reportCompletion(T1, TRADE_DATE);

        // 只报告 1 个 task，未收齐 4 个，不应发布事件
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ==================== D3.3 ====================

    @Test
    void D3_3_fired防重复4次后第5次重复不发布() {
        // 4 个不同任务报告，触发一次发布
        tracker.reportCompletion(T1, TRADE_DATE);
        tracker.reportCompletion(T2, TRADE_DATE);
        tracker.reportCompletion(T3, TRADE_DATE);
        tracker.reportCompletion(T4, TRADE_DATE);
        verify(eventPublisher, times(1)).publishEvent(any());

        // 第 5 次重复报告（T1 再次），不应再发布
        tracker.reportCompletion(T1, TRADE_DATE);
        verify(eventPublisher, times(1)).publishEvent(any());
    }

    // ==================== D3.4 ====================

    @Test
    void D3_4_hasError感知SCHEDULED_PARTIAL() {
        // T1 报告 hasError=true
        tracker.reportCompletion(T1, TRADE_DATE, true);
        tracker.reportCompletion(T2, TRADE_DATE);
        tracker.reportCompletion(T3, TRADE_DATE);
        tracker.reportCompletion(T4, TRADE_DATE);

        ArgumentCaptor<DataBatchReadyEvent> captor = ArgumentCaptor.forClass(DataBatchReadyEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());

        DataBatchReadyEvent event = captor.getValue();
        assertThat(event.getSource()).isEqualTo("SCHEDULED_PARTIAL");
    }

    // ==================== D3.5 ====================

    @Test
    void D3_5_超时兜底forceFireOnTimeout发布SCHEDULED_TIMEOUT() {
        // 先报告 2 个任务（未收齐）
        tracker.reportCompletion(T1, TRADE_DATE);
        tracker.reportCompletion(T2, TRADE_DATE);
        verify(eventPublisher, never()).publishEvent(any());

        // 模拟超时兜底调用
        Set<String> missing = new HashSet<>();
        missing.add(T3);
        missing.add(T4);
        tracker.forceFireOnTimeout(TRADE_DATE, missing);

        ArgumentCaptor<DataBatchReadyEvent> captor = ArgumentCaptor.forClass(DataBatchReadyEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());

        DataBatchReadyEvent event = captor.getValue();
        assertThat(event.getTradeDate()).isEqualTo(TRADE_DATE);
        assertThat(event.getSource()).isEqualTo("SCHEDULED_TIMEOUT");
    }

    // ==================== D3.5b：已 fired 后超时兜底不再重复发布 ====================

    @Test
    void D3_5b_已fired后超时兜底不重复发布() {
        // 正常收齐 4 个，已 fired
        tracker.reportCompletion(T1, TRADE_DATE);
        tracker.reportCompletion(T2, TRADE_DATE);
        tracker.reportCompletion(T3, TRADE_DATE);
        tracker.reportCompletion(T4, TRADE_DATE);
        verify(eventPublisher, times(1)).publishEvent(any());

        // 超时兜底不应再触发（entry 已被 remove，forceFireOnTimeout 内 entry==null 不发布）
        Set<String> missing = new HashSet<>();
        tracker.forceFireOnTimeout(TRADE_DATE, missing);
        verify(eventPublisher, times(1)).publishEvent(any());
    }
}
