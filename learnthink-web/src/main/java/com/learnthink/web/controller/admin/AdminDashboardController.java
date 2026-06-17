package com.learnthink.web.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnthink.common.result.Result;
import com.learnthink.core.domain.entity.Course;
import com.learnthink.core.domain.entity.KnowledgeDocument;
import com.learnthink.core.domain.entity.Task;
import com.learnthink.core.domain.entity.User;
import com.learnthink.core.repository.CourseMapper;
import com.learnthink.core.repository.KnowledgeDocumentMapper;
import com.learnthink.core.repository.TaskMapper;
import com.learnthink.core.repository.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final UserMapper userMapper;
    private final CourseMapper courseMapper;
    private final TaskMapper taskMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    @GetMapping("/stats")
    public Result<Map<String, Object>> getStats() {
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);

        long totalUsers = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getRole, "student"));
        long newUsersThisWeek = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getRole, "student").ge(User::getCreatedAt, weekAgo));
        long activeCourses = courseMapper.selectCount(
                new LambdaQueryWrapper<Course>().isNull(Course::getDeletedAt).eq(Course::getEnabled, true));
        long totalTasks = taskMapper.selectCount(null);
        long tasksThisWeek = taskMapper.selectCount(
                new LambdaQueryWrapper<Task>().ge(Task::getCreatedAt, weekAgo));
        long activeStudentsThisWeek = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getRole, "student").ge(User::getUpdatedAt, weekAgo));
        long totalDocuments = knowledgeDocumentMapper.selectCount(null);
        Long totalChunks = knowledgeDocumentMapper.selectList(null).stream()
                .filter(d -> d.getChunkCount() != null)
                .mapToLong(KnowledgeDocument::getChunkCount)
                .sum();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalUsers", totalUsers);
        data.put("newUsersThisWeek", newUsersThisWeek);
        data.put("activeCourses", activeCourses);
        data.put("totalTasks", totalTasks);
        data.put("tasksThisWeek", tasksThisWeek);
        data.put("taskSuccessRate", 0);
        data.put("activeStudentsThisWeek", activeStudentsThisWeek);
        data.put("totalDocuments", totalDocuments);
        data.put("totalChunks", totalChunks);

        return Result.success(data);
    }

    @GetMapping("/teachers")
    public Result<List<Map<String, Object>>> listTeachers() {
        List<User> teachers = userMapper.selectList(
                new LambdaQueryWrapper<User>()
                        .eq(User::getRole, "teacher")
                        .eq(User::getStatus, "enabled")
        );
        List<Map<String, Object>> result = teachers.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("username", t.getUsername());
            m.put("displayName", t.getDisplayName());
            m.put("email", t.getEmail());
            return m;
        }).toList();
        return Result.success(result);
    }

    @GetMapping("/health")
    public Result<Map<String, Object>> getHealth() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rag", Map.of("status", "up", "latency", 0));
        data.put("milvus", Map.of("status", "up"));
        data.put("llm", Map.of("status", "up", "successRate", 100));
        return Result.success(data);
    }
}
