package com.arthur.stock.service.impl;

import com.arthur.stock.exception.BusinessException;
import com.arthur.stock.exception.ErrorCode;
import com.arthur.stock.mapper.WatchlistGroupMapper;
import com.arthur.stock.mapper.WatchlistMapper;
import com.arthur.stock.model.WatchlistGroupDO;
import com.arthur.stock.model.WatchlistItemDO;
import com.arthur.stock.service.WatchlistGroupService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 自选股分组服务实现
 */
@Service
@RequiredArgsConstructor
public class WatchlistGroupServiceImpl implements WatchlistGroupService {

    private final WatchlistGroupMapper watchlistGroupMapper;
    private final WatchlistMapper watchlistMapper;

    @Override
    public List<WatchlistGroupDO> getGroups(Long userId) {
        return watchlistGroupMapper.selectList(
                new LambdaQueryWrapper<WatchlistGroupDO>()
                        .eq(WatchlistGroupDO::getUserId, userId)
                        .orderByAsc(WatchlistGroupDO::getSortOrder)
                        .orderByAsc(WatchlistGroupDO::getId));
    }

    @Override
    public WatchlistGroupDO createGroup(Long userId, String groupName) {
        if (groupName == null || groupName.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分组名称不能为空");
        }

        Long exists = watchlistGroupMapper.selectCount(
                new LambdaQueryWrapper<WatchlistGroupDO>()
                        .eq(WatchlistGroupDO::getUserId, userId)
                        .eq(WatchlistGroupDO::getGroupName, groupName.trim()));
        if (exists > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分组名称已存在");
        }

        WatchlistGroupDO group = new WatchlistGroupDO();
        group.setUserId(userId);
        group.setGroupName(groupName.trim());
        watchlistGroupMapper.insert(group);
        return group;
    }

    @Override
    public void renameGroup(Long userId, Long groupId, String groupName) {
        if (groupName == null || groupName.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分组名称不能为空");
        }

        WatchlistGroupDO group = watchlistGroupMapper.selectOne(
                new LambdaQueryWrapper<WatchlistGroupDO>()
                        .eq(WatchlistGroupDO::getId, groupId)
                        .eq(WatchlistGroupDO::getUserId, userId));
        if (group == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "分组不存在");
        }

        Long exists = watchlistGroupMapper.selectCount(
                new LambdaQueryWrapper<WatchlistGroupDO>()
                        .eq(WatchlistGroupDO::getUserId, userId)
                        .eq(WatchlistGroupDO::getGroupName, groupName.trim())
                        .ne(WatchlistGroupDO::getId, groupId));
        if (exists > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分组名称已存在");
        }

        int updated = watchlistGroupMapper.update(null,
                new LambdaUpdateWrapper<WatchlistGroupDO>()
                        .eq(WatchlistGroupDO::getId, groupId)
                        .eq(WatchlistGroupDO::getUserId, userId)
                        .set(WatchlistGroupDO::getGroupName, groupName.trim()));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "重命名分组失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteGroup(Long userId, Long groupId) {
        WatchlistGroupDO group = watchlistGroupMapper.selectOne(
                new LambdaQueryWrapper<WatchlistGroupDO>()
                        .eq(WatchlistGroupDO::getId, groupId)
                        .eq(WatchlistGroupDO::getUserId, userId));
        if (group == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "分组不存在");
        }

        watchlistMapper.update(null,
                new LambdaUpdateWrapper<WatchlistItemDO>()
                        .eq(WatchlistItemDO::getUserId, userId)
                        .eq(WatchlistItemDO::getGroupId, groupId)
                        .set(WatchlistItemDO::getGroupId, null));

        int deleted = watchlistGroupMapper.delete(
                new LambdaQueryWrapper<WatchlistGroupDO>()
                        .eq(WatchlistGroupDO::getId, groupId)
                        .eq(WatchlistGroupDO::getUserId, userId));
        if (deleted == 0) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "删除分组失败");
        }
    }
}
