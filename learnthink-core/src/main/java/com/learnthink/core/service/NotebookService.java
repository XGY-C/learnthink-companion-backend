package com.learnthink.core.service;

import com.learnthink.common.dto.note.CreateNotebookRequest;
import com.learnthink.common.dto.note.NotebookVO;
import com.learnthink.common.dto.note.UpdateNotebookRequest;

import java.util.List;

public interface NotebookService {
    List<NotebookVO> list(String userId, String courseId);
    NotebookVO create(String userId, CreateNotebookRequest req);
    NotebookVO update(String userId, String id, UpdateNotebookRequest req);
    void softDelete(String userId, String id);
    NotebookVO getDefault(String userId, String courseId);
    String resolveNotebookId(String userId, String courseId, String notebookId);
}
