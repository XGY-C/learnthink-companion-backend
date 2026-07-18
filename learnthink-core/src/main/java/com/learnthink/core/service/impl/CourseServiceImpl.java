package com.learnthink.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnthink.core.domain.entity.Course;
import com.learnthink.core.domain.entity.Profile;
import com.learnthink.core.domain.entity.UserCourseEnrollment;
import com.learnthink.core.domain.entity.BookInfo;
import com.learnthink.core.domain.entity.KnowledgeDocument;
import com.learnthink.core.repository.BookInfoMapper;
import com.learnthink.core.repository.CourseMapper;
import com.learnthink.core.repository.KnowledgeDocumentMapper;
import com.learnthink.core.repository.ProfileMapper;
import com.learnthink.core.repository.UserCourseEnrollmentMapper;
import com.learnthink.core.service.CourseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;


/**
 * 课程服务实现类，提供课程相关的业务逻辑处理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CourseServiceImpl implements CourseService {

    /**
     * 课程数据访问接口
     */
    private final CourseMapper courseMapper;
    /**
     * 用户课程选课数据访问接口
     */
    private final UserCourseEnrollmentMapper enrollmentMapper;
    /**
     * 用户画像数据访问接口
     */
    private final ProfileMapper profileMapper;
    /**
     * 知识文档数据访问接口
     */
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    /**
     * 教材信息数据访问接口
     */
    private final BookInfoMapper bookInfoMapper;

    @Override
    public List<Map<String, Object>> getMyCourses(String userId) {
        // 查询已选课程（JOIN courses 过滤已删除的）
        List<UserCourseEnrollment> enrollments = enrollmentMapper.selectList(
                new LambdaQueryWrapper<UserCourseEnrollment>()
                        .eq(UserCourseEnrollment::getUserId, userId)
                        .orderByDesc(UserCourseEnrollment::getEnrolledAt)
        );

        // 如果没有选课记录，返回空列表
        if (enrollments.isEmpty()) {
            return Collections.emptyList();
        }

        // 提取课程ID列表
        List<String> courseIds = enrollments.stream()
                .map(UserCourseEnrollment::getCourseId)
                .collect(Collectors.toList());

        // 查询课程信息（过滤已删除的课程）
        List<Course> courses = courseMapper.selectList(
                new LambdaQueryWrapper<Course>()
                        .in(Course::getId, courseIds)
                        .isNull(Course::getDeletedAt)
        );

        // 将课程列表转换为Map，方便后续查找
        Map<String, Course> courseMap = courses.stream()
                .collect(Collectors.toMap(Course::getId, c -> c));

        // 构建返回结果
        List<Map<String, Object>> result = new ArrayList<>();
        for (UserCourseEnrollment enrollment : enrollments) {
            Course course = courseMap.get(enrollment.getCourseId());
            if (course == null) continue;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", course.getId());
            item.put("name", course.getName());
            item.put("emoji", course.getEmoji() != null ? course.getEmoji() : "📚");
            item.put("enrolledAt", enrollment.getEnrolledAt());

            // 轻量进度统计
            Map<String, Object> progress = new LinkedHashMap<>();
            progress.put("tasksCompleted", 0);
            progress.put("resourcesGenerated", 0);
            progress.put("pathProgressPercent", 0);
            item.put("progress", progress);

            result.add(item);
        }
        return result;
    }

    @Override
    public List<Course> getAvailableCourses(String userId) {
        // 获取用户已选课程 ID
        List<UserCourseEnrollment> enrollments = enrollmentMapper.selectList(
                new LambdaQueryWrapper<UserCourseEnrollment>()
                        .eq(UserCourseEnrollment::getUserId, userId)
        );
        Set<String> enrolledIds = enrollments.stream()
                .map(UserCourseEnrollment::getCourseId)
                .collect(Collectors.toSet());

        // 查询未选且未删除的课程
        LambdaQueryWrapper<Course> wrapper = new LambdaQueryWrapper<Course>()
                .isNull(Course::getDeletedAt)
                .orderByDesc(Course::getCreatedAt);

        // 如果用户已选课程不为空，则排除已选课程
        if (!enrolledIds.isEmpty()) {
            wrapper.notIn(Course::getId, enrolledIds);
        }

        return courseMapper.selectList(wrapper);
    }

    @Override
    public Map<String, Object> getCourseDetail(String courseId, String userId) {
        Course course = courseMapper.selectById(courseId);

        if (course == null || course.getDeletedAt() != null) {
            return null;
        }

        // 查询选课人数
        long enrolledCount = enrollmentMapper.selectCount(
                new LambdaQueryWrapper<UserCourseEnrollment>()
                        .eq(UserCourseEnrollment::getCourseId, courseId)
        );

        // 查询当前用户是否已选
        long userEnrolled = enrollmentMapper.selectCount(
                new LambdaQueryWrapper<UserCourseEnrollment>()
                        .eq(UserCourseEnrollment::getUserId, userId)
                        .eq(UserCourseEnrollment::getCourseId, courseId)
        );

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", course.getId());
        detail.put("name", course.getName());
        // 构建返回结果
        detail.put("description", course.getDescription());
        detail.put("emoji", course.getEmoji() != null ? course.getEmoji() : "📚");
        detail.put("enrolledCount", enrolledCount);
        detail.put("isEnrolled", userEnrolled > 0);
        detail.put("createdAt", course.getCreatedAt() != null ? course.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : null);

        return detail;
    }

    @Override
    @Transactional
    public void enrollCourse(String userId, String courseId) {
        // 校验课程存在且未删除
        Course course = courseMapper.selectById(courseId);
        if (course == null || course.getDeletedAt() != null) {
            throw new IllegalArgumentException("课程不存在");
        }

        // 校验是否已选
        Long count = enrollmentMapper.selectCount(
                new LambdaQueryWrapper<UserCourseEnrollment>()
                        .eq(UserCourseEnrollment::getUserId, userId)
                        .eq(UserCourseEnrollment::getCourseId, courseId)
        );
        if (count > 0) {
            throw new IllegalStateException("你已经加入该课程了");
        }

        // 创建选课记录
        UserCourseEnrollment enrollment = new UserCourseEnrollment();
        enrollment.setUserId(userId);
        enrollment.setCourseId(courseId);
        enrollmentMapper.insert(enrollment);

        // 初始化画像记录（如果尚未存在）
        Profile existingProfile = profileMapper.selectOne(
                new LambdaQueryWrapper<Profile>()
                        .eq(Profile::getUserId, userId)
                        .eq(Profile::getCourseId, courseId)
        );
        if (existingProfile == null) {
            Profile profile = new Profile();
            profile.setUserId(userId);
            profile.setCourseId(courseId);
            profile.setCurrentVersion(0);
            profile.setUpdatedAt(LocalDateTime.now());
            profileMapper.insert(profile);
        }
    }

    @Override
    @Transactional
    public void leaveCourse(String userId, String courseId) {
        // 校验是否已选
        UserCourseEnrollment enrollment = enrollmentMapper.selectOne(
                new LambdaQueryWrapper<UserCourseEnrollment>()
                        .eq(UserCourseEnrollment::getUserId, userId)
                        .eq(UserCourseEnrollment::getCourseId, courseId)
        );
        if (enrollment == null) {
            throw new IllegalStateException("你未加入该课程");
        }

        enrollmentMapper.deleteById(enrollment.getId());
    }

    @Override
    public Map<String, Object> getTextbookInfo(String courseId) {
        log.info("getTextbookInfo: courseId={}", courseId);

        List<KnowledgeDocument> docs = knowledgeDocumentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getCourseId, courseId)
                        .eq(KnowledgeDocument::getSourceType, "主教材")
        );
        if (docs.isEmpty()) {
            log.warn("getTextbookInfo: no KnowledgeDocument with source_type='主教材' for courseId={}", courseId);
            return null;
        }

        for (KnowledgeDocument doc : docs) {
            BookInfo book = bookInfoMapper.selectOne(
                    new LambdaQueryWrapper<BookInfo>()
                            .eq(BookInfo::getDocumentId, doc.getId())
            );
            if (book == null) {
                log.debug("getTextbookInfo: no BookInfo for docId={}, skip", doc.getId());
                continue;
            }
            log.info("getTextbookInfo: found book title={}, author={}", book.getTitle(), book.getAuthor());

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("title", book.getTitle() != null ? book.getTitle() : "");
            info.put("author", book.getAuthor() != null ? book.getAuthor() : "");
            info.put("introduction", book.getIntroduction() != null ? book.getIntroduction() : "");
            info.put("toc", book.getToc() != null ? book.getToc() : "[]");
            return info;
        }

        log.warn("getTextbookInfo: {} docs found, but none have book_info", docs.size());
        return null;
    }
}
