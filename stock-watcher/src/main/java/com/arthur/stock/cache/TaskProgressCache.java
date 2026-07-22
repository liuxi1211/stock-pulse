package com.arthur.stock.cache;

import com.arthur.stock.dto.governance.TaskProgress;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 任务进度缓存：基于 Caffeine 的内存缓存，管理数据拉取任务的实时进度。
 * <p>
 * 使用全局锁（AtomicBoolean）确保同一时刻只有一个任务运行。
 * 进度条目写入后 30 分钟自动过期。
 */
@Slf4j
@Component
public class TaskProgressCache {

    private final Cache<String, TaskProgress> progressCache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .build();

    private final AtomicBoolean taskLock = new AtomicBoolean(false);
    private final AtomicBoolean checkLock = new AtomicBoolean(false);

    /** 尝试获取拉取任务全局锁，返回是否成功 */
    public boolean tryAcquireLock() {
        return taskLock.compareAndSet(false, true);
    }

    /** 释放拉取任务全局锁 */
    public void releaseLock() {
        taskLock.set(false);
    }

    /** 检查是否有拉取任务正在运行 */
    public boolean isLocked() {
        return taskLock.get();
    }

    /** 尝试获取检测任务全局锁，返回是否成功 */
    public boolean tryAcquireCheckLock() {
        return checkLock.compareAndSet(false, true);
    }

    /** 释放检测任务全局锁 */
    public void releaseCheckLock() {
        checkLock.set(false);
    }

    /** 检查是否有检测任务正在运行 */
    public boolean isCheckLocked() {
        return checkLock.get();
    }

    /** 存储或更新任务进度 */
    public void putProgress(String taskId, TaskProgress progress) {
        progressCache.put(taskId, progress);
    }

    /** 获取任务进度 */
    public TaskProgress getProgress(String taskId) {
        return progressCache.getIfPresent(taskId);
    }

    /** 设置取消标志 */
    public void setCancelled(String taskId, boolean cancelled) {
        TaskProgress progress = progressCache.getIfPresent(taskId);
        if (progress != null) {
            progress.setCancelled(cancelled);
        }
    }

    /** 检查任务是否已被取消 */
    public boolean isCancelled(String taskId) {
        TaskProgress progress = progressCache.getIfPresent(taskId);
        return progress != null && progress.isCancelled();
    }

    /** 手动移除任务进度 */
    public void remove(String taskId) {
        progressCache.invalidate(taskId);
    }
}
