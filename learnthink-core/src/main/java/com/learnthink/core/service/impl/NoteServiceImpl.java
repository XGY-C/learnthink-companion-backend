package com.learnthink.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnthink.common.dto.note.CreateNoteRequest;
import com.learnthink.common.dto.note.NoteStatsVO;
import com.learnthink.common.dto.note.NoteVO;
import com.learnthink.common.dto.note.UpdateNoteRequest;
import com.learnthink.common.exception.BusinessException;
import com.learnthink.common.exception.ErrorCode;
import com.learnthink.core.domain.entity.Note;
import com.learnthink.core.repository.NoteMapper;
import com.learnthink.core.service.NoteService;
import com.learnthink.core.service.NotebookService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NoteServiceImpl implements NoteService {

    private final NoteMapper noteMapper;
    private final NotebookService notebookService;

    @Override
    public List<NoteVO> list(String userId, String courseId, String notebookId, String resourcePackId,
                              String resourceItemId) {
        LambdaQueryWrapper<Note> wrapper = new LambdaQueryWrapper<Note>()
                .eq(Note::getUserId, userId)
                .eq(Note::getCourseId, courseId)
                .isNull(Note::getDeletedAt);
        if (StringUtils.hasText(notebookId)) {
            wrapper.eq(Note::getNotebookId, notebookId);
        }
        if (StringUtils.hasText(resourcePackId)) {
            wrapper.eq(Note::getResourcePackId, resourcePackId);
        }
        if (StringUtils.hasText(resourceItemId)) {
            wrapper.eq(Note::getResourceItemId, resourceItemId);
        }
        wrapper.orderByDesc(Note::getCreatedAt);
        return noteMapper.selectList(wrapper).stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public NoteVO create(String userId, CreateNoteRequest req) {
        Note note = new Note();
        note.setUserId(userId);
        note.setCourseId(req.getCourseId());
        note.setNotebookId(notebookService.resolveNotebookId(userId, req.getCourseId(), req.getNotebookId()));
        note.setResourcePackId(req.getResourcePackId());
        note.setResourceItemId(req.getResourceItemId());
        note.setResourceTitle(req.getResourceTitle());
        note.setSectionTitle(req.getSectionTitle());
        note.setSelectedText(req.getSelectedText());
        note.setAnchorId(req.getAnchorId());
        note.setTextRange(req.getTextRange());
        note.setContent(req.getContent());
        noteMapper.insert(note);
        return toVO(note);
    }

    @Override
    public NoteVO update(String id, String userId, UpdateNoteRequest req) {
        Note note = noteMapper.selectById(id);
        if (note == null || !note.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "笔记不存在或无权限修改");
        }
        note.setContent(req.getContent());
        noteMapper.updateById(note);
        return toVO(note);
    }

    @Override
    public void softDelete(String id, String userId) {
        Note note = noteMapper.selectById(id);
        if (note == null || !note.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "笔记不存在或无权限删除");
        }
        note.setDeletedAt(LocalDateTime.now());
        noteMapper.updateById(note);
    }

    @Override
    public NoteStatsVO stats(String userId, String courseId) {
        List<Note> notes = noteMapper.listByUserAndCourse(userId, courseId);
        NoteStatsVO stats = new NoteStatsVO();
        stats.setTotalCount(notes.size());
        stats.setByResource(notes.stream()
                .filter(n -> n.getResourcePackId() != null)
                .collect(Collectors.groupingBy(
                    n -> n.getResourcePackId() + "::" + (n.getResourceTitle() != null ? n.getResourceTitle() : ""),
                    Collectors.counting()
                ))
                .entrySet().stream()
                .map(e -> {
                    String[] parts = e.getKey().split("::", 2);
                    NoteStatsVO.ResourceStats rs = new NoteStatsVO.ResourceStats();
                    rs.setPackId(parts[0]);
                    rs.setResourceTitle(parts.length > 1 ? parts[1] : "");
                    rs.setCount(e.getValue().intValue());
                    return rs;
                })
                .collect(Collectors.toList()));
        return stats;
    }

    private NoteVO toVO(Note note) {
        NoteVO vo = new NoteVO();
        vo.setId(note.getId());
        vo.setUserId(note.getUserId());
        vo.setCourseId(note.getCourseId());
        vo.setNotebookId(note.getNotebookId());
        vo.setResourcePackId(note.getResourcePackId());
        vo.setResourceItemId(note.getResourceItemId());
        vo.setResourceTitle(note.getResourceTitle());
        vo.setSectionTitle(note.getSectionTitle());
        vo.setSelectedText(note.getSelectedText());
        vo.setAnchorId(note.getAnchorId());
        vo.setTextRange(note.getTextRange());
        vo.setContent(note.getContent());
        vo.setCreatedAt(note.getCreatedAt() != null ? note.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toString() : null);
        vo.setUpdatedAt(note.getUpdatedAt() != null ? note.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant().toString() : null);
        return vo;
    }
}
