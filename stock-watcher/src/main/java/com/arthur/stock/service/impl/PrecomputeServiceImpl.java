package com.arthur.stock.service.impl;

import com.arthur.stock.service.PrecomputeService;
import com.arthur.stock.service.precompute.PrecomputeJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * PrecomputeService 实现：注入所有 {@link PrecomputeJob} Bean，提供按名/全量手动触发。
 * <p>
 * Spring 会自动将容器内所有 {@code PrecomputeJob} 类型 Bean 注入到 {@link List} 中，
 * 新增 Job 只需注册为 Bean 即可被本服务发现，无需修改此处。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrecomputeServiceImpl implements PrecomputeService {

    /** 所有 PrecomputeJob Bean（Spring 自动收集）。 */
    private final List<PrecomputeJob> jobs;

    @Override
    public void precomputeNow(String jobName, String tradeDate) {
        for (PrecomputeJob job : jobs) {
            if (job.name().equals(jobName)) {
                job.precompute(tradeDate);
                return;
            }
        }
        throw new IllegalArgumentException("Unknown job: " + jobName);
    }

    @Override
    public void precomputeAll(String tradeDate) {
        for (PrecomputeJob job : jobs) {
            try {
                job.precompute(tradeDate);
            } catch (Exception e) {
                log.error("[Precompute] precomputeAll job={} tradeDate={} 失败: {}",
                        job.name(), tradeDate, e.getMessage(), e);
                // 单个 Job 失败不中断后续 Job
            }
        }
    }
}
