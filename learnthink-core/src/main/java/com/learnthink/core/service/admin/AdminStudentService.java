package com.learnthink.core.service.admin;

import com.learnthink.common.dto.admin.AdminStudentResponse;
import java.util.List;

public interface AdminStudentService {
    List<AdminStudentResponse> listStudents(String search, String grade, String major, String status, String courseId);
    void updateStudentStatus(String studentId, String status);
}
