package com.hospital.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;

/**
 * 当前请求的用户上下文，用于检索阶段的权限过滤。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserContext {

    /** 用户唯一标识（工号 / 账号）。 */
    private String userId;

    /** 用户姓名。 */
    private String name;

    /** 所属科室集合，例如 {@code ["心内科", "急诊科"]}。 */
    @Builder.Default
    private Set<String> departments = new HashSet<>();

    /** 角色集合，例如 {@code ["DOCTOR", "ADMIN"]}。 */
    @Builder.Default
    private Set<String> roles = new HashSet<>();

    /** 用户密级，数值越大权限越高。 */
    @Builder.Default
    private int clearanceLevel = 0;

    /** 是否拥有管理员角色（可访问全部资源）。 */
    public boolean isAdmin() {
        return roles != null && (roles.contains("ADMIN") || roles.contains("SUPER_ADMIN"));
    }

    /** 构造一个仅具备基础权限的匿名/访客上下文。 */
    public static UserContext guest() {
        return UserContext.builder()
                .userId("anonymous")
                .name("guest")
                .clearanceLevel(0)
                .build();
    }
}
