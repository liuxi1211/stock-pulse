package com.arthur.stock.controller;

import com.arthur.stock.annotation.RequireAdmin;
import com.arthur.stock.context.UserContext;
import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.CreateUserRequestDTO;
import com.arthur.stock.exception.BusinessException;
import com.arthur.stock.exception.ErrorCode;
import com.arthur.stock.model.Role;
import com.arthur.stock.model.UserDO;
import com.arthur.stock.service.UserService;
import com.arthur.stock.util.TotpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户管理API控制器，仅管理员可访问
 */
@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@RequireAdmin
public class UserApiController {

    private final UserService userService;

    /**
     * 分页查询用户列表，支持按关键字模糊搜索
     */
    @GetMapping
    public ApiResponse<Page<UserDO>> list(@RequestParam(required = false) String keyword,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "10") int size) {
        Page<UserDO> result = userService.listUsers(keyword, page, size);
        return ApiResponse.success(result);
    }

    /**
     * 创建新用户，返回用户信息及TOTP设置链接
     */
    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody @Valid CreateUserRequestDTO req) {
        Role role = null;
        if (req.getRole() != null && !req.getRole().isBlank()) {
            try {
                role = Role.valueOf(req.getRole());
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.INVALID_ROLE);
            }
        }

        UserDO user = userService.createUser(req.getUsername(), req.getPassword(), req.getEmail(), req.getPhone(), role);
        String secret = user.getTotpSecret();
        user.setTotpSecret(null);
        String otpAuthUrl = TotpUtil.getOtpAuthUrl(req.getUsername(), secret);
        return ApiResponse.success(Map.of(
                "user", user,
                "otpAuthUrl", otpAuthUrl,
                "secret", secret
        ));
    }

    /**
     * 删除指定用户，不允许删除自己
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        UserDO currentUser = UserContext.get();
        if (currentUser.getId().equals(id)) {
            throw new BusinessException(ErrorCode.SELF_DELETE);
        }

        userService.deleteUser(id);
        return ApiResponse.success("删除成功", null);
    }

    /**
     * 重置指定用户的TOTP认证密钥，返回新密钥和设置链接
     */
    @PostMapping("/{id}/reset-totp")
    public ApiResponse<Map<String, String>> resetTotp(@PathVariable Long id) {
        UserDO user = userService.findById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        String newSecret = userService.resetTotp(id);
        String otpAuthUrl = TotpUtil.getOtpAuthUrl(user.getUsername(), newSecret);
        return ApiResponse.success(Map.of(
                "secret", newSecret,
                "otpAuthUrl", otpAuthUrl
        ));
    }

}