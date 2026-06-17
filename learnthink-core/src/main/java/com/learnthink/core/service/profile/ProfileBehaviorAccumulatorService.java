package com.learnthink.core.service.profile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnthink.common.dto.profile.BehaviorAccumulatorDto;
import com.learnthink.core.domain.entity.ProfileBehaviorAccumulator;
import com.learnthink.core.repository.ProfileBehaviorAccumulatorMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ProfileBehaviorAccumulatorService {

    private static final Logger log = LoggerFactory.getLogger(ProfileBehaviorAccumulatorService.class);

    private final ProfileBehaviorAccumulatorMapper accumulatorMapper;

    @Value("${learnthink.profile.behavior.threshold:2}")
    private int behaviorThreshold;

    public ProfileBehaviorAccumulatorService(ProfileBehaviorAccumulatorMapper accumulatorMapper) {
        this.accumulatorMapper = accumulatorMapper;
    }

    public List<ProfileBehaviorAccumulator> loadByUserAndCourse(String userId, String courseId) {
        return accumulatorMapper.selectList(
                new LambdaQueryWrapper<ProfileBehaviorAccumulator>()
                        .eq(ProfileBehaviorAccumulator::getUserId, userId)
                        .eq(ProfileBehaviorAccumulator::getCourseId, courseId));
    }

    @Transactional
    public void processBehaviorRecords(String userId, String courseId,
                                        List<BehaviorAccumulatorDto> records) {
        if (records == null || records.isEmpty()) {
            return;
        }

        for (BehaviorAccumulatorDto record : records) {
            if ("promoted".equals(record.getStatus())) {
                if (record.getOccurrenceCount() >= behaviorThreshold) {
                    deleteAccumulator(userId, courseId, record.getSignalKey());
                } else {
                    upsertAccumulator(userId, courseId, record);
                }
            } else if ("accumulating".equals(record.getStatus())) {
                upsertAccumulator(userId, courseId, record);
            }
        }
    }

    private void upsertAccumulator(String userId, String courseId, BehaviorAccumulatorDto dto) {
        LambdaQueryWrapper<ProfileBehaviorAccumulator> query = new LambdaQueryWrapper<ProfileBehaviorAccumulator>()
                .eq(ProfileBehaviorAccumulator::getUserId, userId)
                .eq(ProfileBehaviorAccumulator::getCourseId, courseId)
                .eq(ProfileBehaviorAccumulator::getSignalKey, dto.getSignalKey());

        ProfileBehaviorAccumulator existing = accumulatorMapper.selectOne(query);
        LocalDateTime now = LocalDateTime.now();

        if (existing != null) {
            existing.setOccurrenceCount(dto.getOccurrenceCount());
            existing.setLastObservedAt(now);
            accumulatorMapper.updateById(existing);
        } else {
            ProfileBehaviorAccumulator acc = new ProfileBehaviorAccumulator();
            acc.setUserId(userId);
            acc.setCourseId(courseId);
            acc.setSignalKey(dto.getSignalKey());
            acc.setValue(dto.getValue());
            acc.setOccurrenceCount(dto.getOccurrenceCount());
            acc.setFirstObservedAt(now);
            acc.setLastObservedAt(now);
            accumulatorMapper.insert(acc);
        }
    }

    private void deleteAccumulator(String userId, String courseId, String signalKey) {
        accumulatorMapper.delete(
                new LambdaQueryWrapper<ProfileBehaviorAccumulator>()
                        .eq(ProfileBehaviorAccumulator::getUserId, userId)
                        .eq(ProfileBehaviorAccumulator::getCourseId, courseId)
                        .eq(ProfileBehaviorAccumulator::getSignalKey, signalKey));
    }
}
