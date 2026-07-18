package com.learnthink.core.service;

import com.learnthink.common.dto.note.CreateNoteRequest;
import com.learnthink.common.dto.note.NoteStatsVO;
import com.learnthink.common.dto.note.NoteVO;
import com.learnthink.common.dto.note.UpdateNoteRequest;
import java.util.List;

public interface NoteService {
    List<NoteVO> list(String userId, String courseId, String notebookId, String resourcePackId, String resourceItemId);
    NoteVO create(String userId, CreateNoteRequest req);
    NoteVO update(String id, String userId, UpdateNoteRequest req);
    void softDelete(String id, String userId);
    NoteStatsVO stats(String userId, String courseId);
}
