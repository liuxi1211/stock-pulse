package com.arthur.stock.cache;

import com.arthur.stock.dto.governance.TaskProgress;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 任务进度缓存：基于 Caffeine 的内存缓存，管理数据拉取任务的实时进度。
 * <p>
 * 使用带超时的全局锁（AtomicReference 持有 taskId+时间戳）确保同一时刻只有一个任务运行。
 * 锁自带超时（默认 2 小时），任务线程需定期调用 heartbeat 续期，防止异常退出导致永久死锁。
 * 进度条目写入后 30 分钟自动过期。
 */
@Slf4j
@Component
public class TaskProgressCache {

    private final Cache<String, TaskProgress> progressCache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .build();

    private static final long LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L;

    private static class LockHolder {
        final String taskId;
        volatile long lastHeartbeatMs;

        LockHolder(String taskId) {
            this.taskId = taskId;
            this.lastHeartbeatMs = System.currentTimeMillis();
        }
    }

    private final AtomicReference<LockHolder> taskLock = new AtomicReference<>(null);
    private final AtomicReference<LockHolder> checkLock = new AtomicReference<>(null);

    /** 尝试获取拉取任务全局锁，返回是否成功 */
    public boolean tryAcquireLock() {
        return tryAcquireLock(taskLock, UUID());
    }

    private boolean tryAcquireLock(AtomicReference<LockHolder> lockRef, String taskId) {
        LockHolder current = lockRef.get();
        if (current != null) {
            long age = System.currentTimeMillis() - current.lastHeartbeatMs;
            if (age < LOCK_TIMEOUT_MS) {
                return false;
            }
            log.warn("Lock held by task {} expired (age={}ms), force releasing", current.taskId, age);
            lockRef.compareAndSet(current, null);
        }
        LockHolder newHolder = new LockHolder(taskId);
        return lockRef.compareAndSet(null, newHolder);
    }

    /** 释放拉取任务全局锁 */
    public void releaseLock() {
        releaseLock(taskLock);
    }

    private void releaseLock(AtomicReference<LockHolder> lockRef) {
        LockHolder holder = lockRef.get();
        if (holder != null) {
            lockRef.set(null);
        }
    }

    /** 检查是否有拉取任务正在运行 */
    public boolean isLocked() {
        return isLocked(taskLock);
    }

    private boolean isLocked(AtomicReference<LockHolder> lockRef) {
        LockHolder current = lockRef.get();
        if (current == null) {
            return false;
        }
        long age = System.currentTimeMillis() - current.lastHeartbeatMs;
        return age < LOCK_TIMEOUT_MS;
    }

    /** 心跳续期（任务线程定期调用，防止锁超时） */
    public boolean heartbeat(String taskId) {
        return heartbeat(taskLock, taskId) && heartbeat(checkLock, taskId);
    }

    private boolean heartbeat(AtomicReference<LockHolder> lockRef, String taskId) {
        LockHolder current = lockRef.get();
        if (current != null && current.taskId.equals(taskId)) {
            current.lastHeartbeatMs = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    /** 尝试获取检测任务全局锁，返回是否成功 */
    public boolean tryAcquireCheckLock() {
        return tryAcquireLock(checkLock, "check-" + UUID());
    }

    /** 释放检测任务全局锁 */
    public void releaseCheckLock() {
        releaseLock(checkLock);
    }

    /** 检查是否有检测任务正在运行 */
    public boolean isCheckLocked() {
        return isLocked(checkLock);
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

    private static String UUID() {
        return java.util.UUID.randomUUID().toString();
    }
}
