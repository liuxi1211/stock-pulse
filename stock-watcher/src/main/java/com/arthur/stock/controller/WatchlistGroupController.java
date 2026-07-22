package com.arthur.stock.controller;

import com.arthur.stock.context.UserContext;
import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.model.WatchlistGroupDO;
import com.arthur.stock.service.WatchlistGroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "自选股分组管理", description = "用户自选股分组的增删改查")
@RestController
@RequestMapping("/watchlist/groups")
@RequiredArgsConstructor
public class WatchlistGroupController {

    private final WatchlistGroupService watchlistGroupService;

    @Operation(summary = "获取分组列表", description = "获取当前登录用户的所有自定义分组")
    @GetMapping
    public ApiResponse<List<WatchlistGroupDO>> getGroups() {
        return ApiResponse.success(watchlistGroupService.getGroups(UserContext.getUserId()));
    }

    @Operation(summary = "创建分组", description = "创建一个新的自选股分组")
    @PostMapping
    public ApiResponse<WatchlistGroupDO> createGroup(
            @Parameter(description = "分组名称", required = true)
            @RequestParam String groupName) {
        return ApiResponse.success("创建成功", watchlistGroupService.createGroup(UserContext.getUserId(), groupName));
    }

    @Operation(summary = "重命名分组", description = "修改指定分组的名称")
    @PutMapping("/{groupId}")
    public ApiResponse<Void> renameGroup(
            @Parameter(description = "分组ID", required = true)
            @PathVariable Long groupId,
            @Parameter(description = "新的分组名称", required = true)
            @RequestParam String groupName) {
        watchlistGroupService.renameGroup(UserContext.getUserId(), groupId, groupName);
        return ApiResponse.success("重命名成功", null);
    }

    @Operation(summary = "删除分组", description = "删除指定分组，组内股票的 group_id 将被置为 null（变为未分组）")
    @DeleteMapping("/{groupId}")
    public ApiResponse<Void> deleteGroup(
            @Parameter(description = "分组ID", required = true)
            @PathVariable Long groupId) {
        watchlistGroupService.deleteGroup(UserContext.getUserId(), groupId);
        return ApiResponse.success("删除成功", null);
    }
}
