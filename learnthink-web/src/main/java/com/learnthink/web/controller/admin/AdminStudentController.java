package com.learnthink.web.controller.admin;

import com.learnthink.common.dto.admin.AdminStudentResponse;
import com.learnthink.common.dto.admin.UpdateStatusRequest;
import com.learnthink.common.result.Result;
import com.learnthink.core.service.admin.AdminStudentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/students")
@RequiredArgsConstructor
public class AdminStudentController {

    private final AdminStudentService adminStudentService;

    @GetMapping
    public Result<List<AdminStudentResponse>> listStudents(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String grade,
            @RequestParam(required = false) String major,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String courseId) {
        return Result.success(adminStudentService.listStudents(search, grade, major, status, courseId));
    }

    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable String id, @RequestBody UpdateStatusRequest request) {
        adminStudentService.updateStudentStatus(id, request.getStatus());
        return Result.success();
    }
}
