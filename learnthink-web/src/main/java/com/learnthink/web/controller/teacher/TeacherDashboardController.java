package com.learnthink.web.controller.teacher;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.domain.entity.Course;
import com.learnthink.core.domain.entity.ResourceItem;
import com.learnthink.core.domain.entity.UserCourseEnrollment;
import com.learnthink.core.repository.CourseMapper;
import com.learnthink.core.domain.entity.ResourcePack;
import com.learnthink.core.repository.ResourceItemMapper;
import com.learnthink.core.repository.ResourcePackMapper;
import com.learnthink.core.repository.UserCourseEnrollmentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/teacher/dashboard")
@RequiredArgsConstructor
public class TeacherDashboardController {

    private final CourseMapper courseMapper;
    private final UserCourseEnrollmentMapper enrollmentMapper;
    private final ResourceItemMapper resourceItemMapper;
    private final ResourcePackMapper resourcePackMapper;

    @GetMapping
    public Result<Map<String, Object>> dashboard() {
        String userId = UserContextUtil.getCurrentUserId();

        List<Course> myCourses = courseMapper.selectList(
                new LambdaQueryWrapper<Course>()
                        .eq(Course::getTeacherId, userId)
                        .isNull(Course::getDeletedAt)
        );

        List<String> courseIds = myCourses.stream().map(Course::getId).toList();

        int totalStudents = 0;
        int totalResourceItems = 0;
        int pendingReview = 0;

        if (!courseIds.isEmpty()) {
            totalStudents = enrollmentMapper.selectCount(
                    new LambdaQueryWrapper<UserCourseEnrollment>()
                            .in(UserCourseEnrollment::getCourseId, courseIds)
            ).intValue();

            // ResourceItem 无 courseId，通过 ResourcePack 关联
            List<ResourcePack> packs = resourcePackMapper.selectList(
                    new LambdaQueryWrapper<ResourcePack>()
                            .in(ResourcePack::getCourseId, courseIds)
            );
            if (!packs.isEmpty()) {
                Set<String> packIds = packs.stream().map(ResourcePack::getId).collect(Collectors.toSet());
                totalResourceItems = resourceItemMapper.selectCount(
                        new LambdaQueryWrapper<ResourceItem>()
                                .in(ResourceItem::getPackId, packIds)
                ).intValue();
                pendingReview = resourceItemMapper.selectCount(
                        new LambdaQueryWrapper<ResourceItem>()
                                .in(ResourceItem::getPackId, packIds)
                                .eq(ResourceItem::getReviewStatus, "pending")
                ).intValue();
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("courseCount", myCourses.size());
        data.put("totalStudents", totalStudents);
        data.put("totalResourceItems", totalResourceItems);
        data.put("pendingReview", pendingReview);
        data.put("courses", myCourses.stream().map(c -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.getId());
            item.put("name", c.getName());
            item.put("emoji", c.getEmoji());
            return item;
        }).toList());

        return Result.success(data);
    }
}
