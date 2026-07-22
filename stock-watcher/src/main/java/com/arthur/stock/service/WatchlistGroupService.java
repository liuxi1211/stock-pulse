package com.arthur.stock.service;

import com.arthur.stock.model.WatchlistGroupDO;

import java.util.List;

/**
 * 自选股分组服务接口
 */
public interface WatchlistGroupService {

    /**
     * 获取用户的所有自定义分组
     */
    List<WatchlistGroupDO> getGroups(Long userId);

    /**
     * 创建分组
     */
    WatchlistGroupDO createGroup(Long userId, String groupName);

    /**
     * 重命名分组
     */
    void renameGroup(Long userId, Long groupId, String groupName);

    /**
     * 删除分组（组内股票 group_id 置 null）
     */
    void deleteGroup(Long userId, Long groupId);
}
