package com.learnthink.core.service;

import com.learnthink.core.domain.entity.Course;

import java.util.List;
import java.util.Map;

/**
 * 课程服务接口
 */
public interface CourseService {

    /**
     * 获取当前用户已选课程列表（含进度概览）
     */
    List<Map<String, Object>> getMyCourses(String userId);

    /**
     * 获取当前用户可选课程列表（未选且未删除）
     */
    List<Course> getAvailableCourses(String userId);

    /**
     * 获取课程详情（含当前用户是否已选）
     */
    Map<String, Object> getCourseDetail(String courseId, String userId);

    /**
     * 选课
     */
    void enrollCourse(String userId, String courseId);

    /**
     * 退课
     */
    void leaveCourse(String userId, String courseId);

    /**
     * 获取课程教材基本信息（书名、作者、简介、目录）
     * @return textbook info map，无教材数据时返回 null
     */
    Map<String, Object> getTextbookInfo(String courseId);
}
