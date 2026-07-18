package com.learnthink.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.learnthink.common.dto.note.CreateNotebookRequest;
import com.learnthink.common.dto.note.NotebookVO;
import com.learnthink.common.dto.note.UpdateNotebookRequest;
import com.learnthink.common.exception.BusinessException;
import com.learnthink.common.exception.ErrorCode;
import com.learnthink.core.domain.entity.Course;
import com.learnthink.core.domain.entity.Note;
import com.learnthink.core.domain.entity.Notebook;
import com.learnthink.core.domain.entity.UserCourseEnrollment;
import com.learnthink.core.repository.CourseMapper;
import com.learnthink.core.repository.NoteMapper;
import com.learnthink.core.repository.NotebookMapper;
import com.learnthink.core.repository.UserCourseEnrollmentMapper;
import com.learnthink.core.service.NotebookService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NotebookServiceImpl implements NotebookService {

    private static final String DEFAULT_COVER = "default";
    private static final String DEFAULT_NAME = "默认笔记本";

    private final NotebookMapper notebookMapper;
    private final CourseMapper courseMapper;
    private final UserCourseEnrollmentMapper enrollmentMapper;
    private final NoteMapper noteMapper;

    @Override
    public List<NotebookVO> list(String userId, String courseId) {
        ensureCourseAndEnrollment(userId, courseId);
        return notebookMapper.listByUserAndCourse(userId, courseId).stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    @Transactional
    public NotebookVO create(String userId, CreateNotebookRequest req) {
        ensureCourseAndEnrollment(userId, req.getCourseId());

        boolean hasDefault = notebookMapper.selectCount(new LambdaQueryWrapper<Notebook>()
                .eq(Notebook::getUserId, userId)
                .eq(Notebook::getCourseId, req.getCourseId())
            .isNull(Notebook::getDeletedAt)
                .eq(Notebook::getIsDefault, true)) > 0;

        boolean isDefault = Boolean.TRUE.equals(req.getIsDefault()) || !hasDefault;

        Notebook notebook = new Notebook();
        notebook.setUserId(userId);
        notebook.setCourseId(req.getCourseId());
        notebook.setName(req.getName());
        notebook.setDescription(req.getDescription());
        notebook.setCover(StringUtils.hasText(req.getCover()) ? req.getCover() : DEFAULT_COVER);
        notebook.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0);
        notebook.setIsDefault(isDefault);
        notebook.setCreatedAt(LocalDateTime.now());
        notebook.setUpdatedAt(LocalDateTime.now());
        notebookMapper.insert(notebook);

        if (isDefault) {
            notebookMapper.update(null, new LambdaUpdateWrapper<Notebook>()
                    .eq(Notebook::getUserId, userId)
                    .eq(Notebook::getCourseId, req.getCourseId())
                    .ne(Notebook::getId, notebook.getId())
                    .isNull(Notebook::getDeletedAt)
                    .set(Notebook::getIsDefault, false));
        }

        return toVO(notebook);
    }

    @Override
    @Transactional
    public NotebookVO update(String userId, String id, UpdateNotebookRequest req) {
        Notebook notebook = getActiveNotebookForOwner(userId, id);
        boolean wasDefault = Boolean.TRUE.equals(notebook.getIsDefault());

        if (req.getName() != null) notebook.setName(req.getName());
        if (req.getDescription() != null) notebook.setDescription(req.getDescription());
        if (req.getCover() != null) notebook.setCover(req.getCover());
        if (req.getSortOrder() != null) notebook.setSortOrder(req.getSortOrder());

        if (req.getIsDefault() != null) {
            if (Boolean.TRUE.equals(req.getIsDefault())) {
                notebook.setIsDefault(true);
                notebookMapper.update(null, new LambdaUpdateWrapper<Notebook>()
                        .eq(Notebook::getUserId, userId)
                        .eq(Notebook::getCourseId, notebook.getCourseId())
                        .ne(Notebook::getId, notebook.getId())
                        .isNull(Notebook::getDeletedAt)
                        .set(Notebook::getIsDefault, false));
            } else if (wasDefault) {
                Notebook alternate = notebookMapper.selectOne(new LambdaQueryWrapper<Notebook>()
                        .eq(Notebook::getUserId, userId)
                        .eq(Notebook::getCourseId, notebook.getCourseId())
                        .ne(Notebook::getId, notebook.getId())
                        .isNull(Notebook::getDeletedAt)
                        .orderByAsc(Notebook::getSortOrder)
                        .orderByAsc(Notebook::getCreatedAt)
                        .last("LIMIT 1"));
                if (alternate != null) {
                    alternate.setIsDefault(true);
                    alternate.setUpdatedAt(LocalDateTime.now());
                    notebookMapper.updateById(alternate);
                    notebook.setIsDefault(false);
                }
            } else {
                notebook.setIsDefault(false);
            }
        }

        notebook.setUpdatedAt(LocalDateTime.now());
        notebookMapper.updateById(notebook);
        return toVO(notebook);
    }

    @Override
    @Transactional
    public void softDelete(String userId, String id) {
        Notebook notebook = getActiveNotebookForOwner(userId, id);
        boolean wasDefault = Boolean.TRUE.equals(notebook.getIsDefault());

        noteMapper.update(null, new LambdaUpdateWrapper<Note>()
                .eq(Note::getNotebookId, id)
                .set(Note::getNotebookId, null));

        notebook.setDeletedAt(LocalDateTime.now());
        notebook.setIsDefault(false);
        notebook.setUpdatedAt(LocalDateTime.now());
        notebookMapper.updateById(notebook);

        if (wasDefault) {
            Notebook alternate = notebookMapper.selectOne(new LambdaQueryWrapper<Notebook>()
                    .eq(Notebook::getUserId, userId)
                    .eq(Notebook::getCourseId, notebook.getCourseId())
                    .isNull(Notebook::getDeletedAt)
                    .ne(Notebook::getId, notebook.getId())
                    .orderByAsc(Notebook::getSortOrder)
                    .orderByAsc(Notebook::getCreatedAt)
                    .last("LIMIT 1"));
            if (alternate != null) {
                alternate.setIsDefault(true);
                alternate.setUpdatedAt(LocalDateTime.now());
                notebookMapper.updateById(alternate);
            }
        }
    }

    @Override
    public NotebookVO getDefault(String userId, String courseId) {
        ensureCourseAndEnrollment(userId, courseId);
        Notebook notebook = notebookMapper.findDefaultByCourse(userId, courseId);
        if (notebook == null) {
            notebook = createDefaultNotebook(userId, courseId);
        }
        return toVO(notebook);
    }

    @Override
    @Transactional
    public String resolveNotebookId(String userId, String courseId, String notebookId) {
        if (!StringUtils.hasText(notebookId)) {
            return getOrCreateDefaultNotebookId(userId, courseId);
        }
        Notebook notebook = getActiveNotebookForOwnerAndCourse(userId, courseId, notebookId);
        return notebook.getId();
    }

    public String getOrCreateDefaultNotebookId(String userId, String courseId) {
        Notebook notebook = notebookMapper.findDefaultByCourse(userId, courseId);
        if (notebook != null) {
            return notebook.getId();
        }
        return createDefaultNotebook(userId, courseId).getId();
    }

    private Notebook createDefaultNotebook(String userId, String courseId) {
        ensureCourseAndEnrollment(userId, courseId);
        Notebook notebook = new Notebook();
        notebook.setUserId(userId);
        notebook.setCourseId(courseId);
        notebook.setName(DEFAULT_NAME);
        notebook.setDescription(null);
        notebook.setCover(DEFAULT_COVER);
        notebook.setSortOrder(0);
        notebook.setIsDefault(true);
        notebook.setCreatedAt(LocalDateTime.now());
        notebook.setUpdatedAt(LocalDateTime.now());
        notebookMapper.insert(notebook);
        return notebook;
    }

    private void ensureCourseAndEnrollment(String userId, String courseId) {
        Course course = courseMapper.selectById(courseId);
        if (course == null || course.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "课程不存在");
        }

        Long enrollmentCount = enrollmentMapper.selectCount(new LambdaQueryWrapper<UserCourseEnrollment>()
                .eq(UserCourseEnrollment::getUserId, userId)
                .eq(UserCourseEnrollment::getCourseId, courseId));
        if (enrollmentCount == null || enrollmentCount == 0) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "请先加入该课程");
        }
    }

    private Notebook getActiveNotebookForOwner(String userId, String id) {
        Notebook notebook = notebookMapper.selectOne(new LambdaQueryWrapper<Notebook>()
                .eq(Notebook::getId, id)
                .eq(Notebook::getUserId, userId)
                .isNull(Notebook::getDeletedAt));
        if (notebook == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "笔记本不存在");
        }
        return notebook;
    }

    private Notebook getActiveNotebookForOwnerAndCourse(String userId, String courseId, String id) {
        Notebook notebook = notebookMapper.selectOne(new LambdaQueryWrapper<Notebook>()
                .eq(Notebook::getId, id)
                .eq(Notebook::getUserId, userId)
                .eq(Notebook::getCourseId, courseId)
                .isNull(Notebook::getDeletedAt));
        if (notebook == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "笔记本不存在");
        }
        return notebook;
    }

    private NotebookVO toVO(Notebook notebook) {
        NotebookVO vo = new NotebookVO();
        vo.setId(notebook.getId());
        vo.setCourseId(notebook.getCourseId());
        vo.setName(notebook.getName());
        vo.setDescription(notebook.getDescription());
        vo.setCover(notebook.getCover());
        vo.setSortOrder(notebook.getSortOrder());
        vo.setIsDefault(notebook.getIsDefault());
        vo.setCreatedAt(notebook.getCreatedAt() != null ? notebook.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toString() : null);
        vo.setUpdatedAt(notebook.getUpdatedAt() != null ? notebook.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant().toString() : null);
        return vo;
    }
}
