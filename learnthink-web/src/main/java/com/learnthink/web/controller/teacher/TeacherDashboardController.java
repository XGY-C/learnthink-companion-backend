package com.learnthink.web.controller.teacher;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.domain.entity.Course;
import com.learnthink.core.domain.entity.ResourceItem;
import com.learnthink.core.domain.entity.ResourcePack;
import com.learnthink.core.domain.entity.UserCourseEnrollment;
import com.learnthink.core.repository.CourseMapper;
import com.learnthink.core.repository.ResourceItemMapper;
import com.learnthink.core.repository.ResourcePackMapper;
import com.learnthink.core.repository.UserCourseEnrollmentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
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

        Map<String, Integer> courseStudentCount = new HashMap<>();
        Map<String, Integer> courseResourceCount = new HashMap<>();

        if (!courseIds.isEmpty()) {
            // 学生数（按课程聚合）
            List<UserCourseEnrollment> enrollments = enrollmentMapper.selectList(
                    new LambdaQueryWrapper<UserCourseEnrollment>()
                            .in(UserCourseEnrollment::getCourseId, courseIds)
            );
            totalStudents = enrollments.size();
            for (UserCourseEnrollment e : enrollments) {
                courseStudentCount.merge(e.getCourseId(), 1, Integer::sum);
            }

            // 资源数（通过 ResourcePack 关联，按课程聚合）
            List<ResourcePack> packs = resourcePackMapper.selectList(
                    new LambdaQueryWrapper<ResourcePack>()
                            .in(ResourcePack::getCourseId, courseIds)
            );
            if (!packs.isEmpty()) {
                Map<String, List<String>> courseToPackIds = packs.stream()
                        .collect(Collectors.groupingBy(
                                ResourcePack::getCourseId,
                                Collectors.mapping(ResourcePack::getId, Collectors.toList())
                        ));
                Set<String> allPackIds = packs.stream().map(ResourcePack::getId).collect(Collectors.toSet());
                List<ResourceItem> items = resourceItemMapper.selectList(
                        new LambdaQueryWrapper<ResourceItem>()
                                .in(ResourceItem::getPackId, allPackIds)
                );
                totalResourceItems = items.size();
                pendingReview = (int) items.stream()
                        .filter(i -> "pending".equals(i.getReviewStatus()))
                        .count();
                Map<String, Long> packItemCount = items.stream()
                        .collect(Collectors.groupingBy(ResourceItem::getPackId, Collectors.counting()));
                for (Map.Entry<String, List<String>> entry : courseToPackIds.entrySet()) {
                    int sum = entry.getValue().stream()
                            .mapToInt(pid -> packItemCount.getOrDefault(pid, 0L).intValue())
                            .sum();
                    courseResourceCount.put(entry.getKey(), sum);
                }
            }
        }

        final Map<String, Integer> fStudentCount = courseStudentCount;
        final Map<String, Integer> fResourceCount = courseResourceCount;

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
            item.put("studentCount", fStudentCount.getOrDefault(c.getId(), 0));
            item.put("resourceCount", fResourceCount.getOrDefault(c.getId(), 0));
            return item;
        }).toList());

        return Result.success(data);
    }
}
