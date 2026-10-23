package com.hospital.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;

/**
 * 文档级访问控制策略。
 *
 * <p>仅当用户的科室、角色、密级同时满足约束时才可见；
 * {@code publicAccess=true} 表示所有已登录用户均可访问。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccessControl {

    /** 允许访问的科室集合；为空表示不限科室。 */
    @Builder.Default
    private Set<String> departments = new HashSet<>();

    /** 允许访问的角色集合；为空表示不限角色。 */
    @Builder.Default
    private Set<String> roles = new HashSet<>();

    /** 访问所需的最低密级，用户密级须 >= 该值。 */
    @Builder.Default
    private int sensitivityLevel = 0;

    /** 是否对所有用户公开。 */
    @Builder.Default
    private boolean publicAccess = false;

    /**
     * 判断某用户是否可访问该资源。
     *
     * @param user 当前用户上下文，可为 null（视为未登录）
     * @return 是否可访问
     */
    public boolean isAccessible(UserContext user) {
        if (publicAccess) {
            return true;
        }
        if (user == null) {
            return false;
        }
        if (user.isAdmin()) {
            return true;
        }
        boolean deptOk = departments == null || departments.isEmpty()
                || user.getDepartments().stream().anyMatch(departments::contains);
        boolean roleOk = roles == null || roles.isEmpty()
                || user.getRoles().stream().anyMatch(roles::contains);
        boolean levelOk = user.getClearanceLevel() >= sensitivityLevel;
        return deptOk && roleOk && levelOk;
    }

    /** 全局公开资源。 */
    public static AccessControl publicResource() {
        return AccessControl.builder().publicAccess(true).build();
    }
}
