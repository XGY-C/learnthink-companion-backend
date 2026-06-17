package com.learnthink.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.domain.entity.Course;
import com.learnthink.core.repository.CourseMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;
    private final CourseMapper courseMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!UserContextUtil.isLoggedIn()) {
            log.warn("Request without user context: {} {}", request.getMethod(), request.getRequestURI());
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
            response.getWriter().write(
                objectMapper.writeValueAsString(Map.of("code", 401, "message", "未登录或登录已过期"))
            );
            return false;
        }

        String uri = request.getRequestURI();
        String role = UserContextUtil.getCurrentUserRole();

        // 管理员路径需要 admin 角色
        if (uri.startsWith("/admin/")) {
            if (!"admin".equals(role)) {
                log.warn("Non-admin access to admin path: {} {}", request.getMethod(), uri);
                response.setStatus(403);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
                response.getWriter().write(
                    objectMapper.writeValueAsString(Map.of("code", 403, "message", "权限不足，需要管理员权限"))
                );
                return false;
            }
        }

        // 教师路径需要 teacher 或 admin 角色
        if (uri.startsWith("/teacher/")) {
            if (!"teacher".equals(role) && !"admin".equals(role)) {
                log.warn("Non-teacher access to teacher path: {} {}", request.getMethod(), uri);
                response.setStatus(403);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
                response.getWriter().write(
                    objectMapper.writeValueAsString(Map.of("code", 403, "message", "权限不足，需要教师权限"))
                );
                return false;
            }
            // 课程作用域接口校验教师是否为该课程的 teacher_id 持有者
            if (!checkTeacherCourseOwnership(uri, role, response)) {
                return false;
            }
        }

        return true;
    }

    /**
     * 校验教师对该课程是否有操作权限（仅 teacher 角色需要校验，admin 跳过）。
     * 匹配 /teacher/courses/{courseId}/... 路径。
     *
     * @return true 表示通过校验，false 表示拒绝
     */
    private boolean checkTeacherCourseOwnership(String uri, String role, HttpServletResponse response) throws Exception {
        if (!uri.startsWith("/teacher/courses/")) return true;
        if ("admin".equals(role)) return true; // admin 可以管理所有课程

        String[] segments = uri.split("/");
        if (segments.length < 4) return true;
        String courseId = segments[3];
        if (courseId == null || courseId.isBlank()) return true;

        Course course = courseMapper.selectById(courseId);
        if (course == null) return true; // 让 controller 处理 404

        String teacherId = course.getTeacherId();
        String userId = UserContextUtil.getCurrentUserId();

        if (teacherId != null && !teacherId.equals(userId)) {
            log.warn("Teacher {} does not own course {} (teacher_id={})", userId, courseId, teacherId);
            response.setStatus(403);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
            response.getWriter().write(
                objectMapper.writeValueAsString(Map.of("code", 403, "message", "您不是该课程的授课教师，无权操作"))
            );
            return false;
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                 Object handler, Exception ex) {
        UserContextUtil.clear();
    }
}
