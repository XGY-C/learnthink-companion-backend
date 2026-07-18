package com.learnthink.core.service.admin;

import com.learnthink.common.dto.admin.AdminTeacherResponse;
import com.learnthink.core.domain.entity.User;

import java.util.List;

public interface AdminTeacherService {
    List<AdminTeacherResponse> listTeachers(String search, String status);
    AdminTeacherResponse createTeacher(String username, String email, String password, String displayName, String phone);
    AdminTeacherResponse updateTeacher(String id, String displayName, String email, String phone);
    void updateTeacherStatus(String id, String status);
    void resetPassword(String id);
    void deleteTeacher(String id);
}
