package com.learnthink.core.service.profile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnthink.core.domain.entity.ProfileSignal;
import com.learnthink.core.domain.entity.ProfileSignalCooldown;
import com.learnthink.core.repository.ProfileSignalCooldownMapper;
import com.learnthink.core.repository.ProfileSignalMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ProfileSignalService {

    private static final Logger log = LoggerFactory.getLogger(ProfileSignalService.class);

    private final ProfileSignalMapper signalMapper;
    private final ProfileSignalCooldownMapper cooldownMapper;

    @Value("${learnthink.profile.cooldown.days:30}")
    private int cooldownDays;

    @Value("${learnthink.profile.cooldown.max-cycles:2}")
    private int maxCooldownCycles;

    public ProfileSignalService(ProfileSignalMapper signalMapper,
                                ProfileSignalCooldownMapper cooldownMapper) {
        this.signalMapper = signalMapper;
        this.cooldownMapper = cooldownMapper;
    }

    @Transactional
    public void batchInsertSignals(List<ProfileSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return;
        }
        for (ProfileSignal signal : signals) {
            signal.setCreatedAt(LocalDateTime.now());
            signalMapper.insert(signal);
        }
        log.debug("Batch inserted {} profile signals", signals.size());
    }

    public List<ProfileSignal> loadPendingConfirmations(String userId, String courseId) {
        LambdaQueryWrapper<ProfileSignal> query = new LambdaQueryWrapper<ProfileSignal>()
                .eq(ProfileSignal::getUserId, userId)
                .eq(ProfileSignal::getCourseId, courseId)
                .eq(ProfileSignal::getSource, "llm_inferred")
                .eq(ProfileSignal::getStatus, "pending")
                .notIn(ProfileSignal::getSignalKey,
                        new LambdaQueryWrapper<ProfileSignalCooldown>()
                                .select(ProfileSignalCooldown::getSignalKey)
                                .eq(ProfileSignalCooldown::getUserId, userId)
                                .eq(ProfileSignalCooldown::getCourseId, courseId)
                                .gt(ProfileSignalCooldown::getCooldownUntil, LocalDateTime.now()))
                .orderByAsc(ProfileSignal::getCreatedAt)
                .last("LIMIT 3");
        return signalMapper.selectList(query);
    }

    @Transactional
    public void handleConfirmation(String signalId, boolean confirmed) {
        ProfileSignal signal = signalMapper.selectById(signalId);
        if (signal == null) {
            log.warn("Signal not found for confirmation: {}", signalId);
            return;
        }
        if (confirmed) {
            signal.setStatus("written");
            signalMapper.updateById(signal);
            log.debug("Confirmed signal {} -> written", signalId);
        } else {
            signal.setStatus("discarded");
            signalMapper.updateById(signal);
            applyCooldown(signal);
            log.debug("Rejected signal {} -> discarded + cooldown", signalId);
        }
    }

    @Transactional
    public void handleUnmentionedItems(String userId, String courseId) {
        List<ProfileSignal> pendingItems = signalMapper.selectList(
                new LambdaQueryWrapper<ProfileSignal>()
                        .eq(ProfileSignal::getUserId, userId)
                        .eq(ProfileSignal::getCourseId, courseId)
                        .eq(ProfileSignal::getSource, "llm_inferred")
                        .eq(ProfileSignal::getStatus, "pending")
                        .orderByAsc(ProfileSignal::getCreatedAt));

        int silenceCount = 0;
        for (ProfileSignal item : pendingItems) {
            if (silenceCount >= 2) {
                break;
            }
            silenceCount++;
        }

        if (silenceCount >= 2) {
            List<ProfileSignal> toDiscard = pendingItems.subList(0, silenceCount);
            for (ProfileSignal item : toDiscard) {
                item.setStatus("discarded");
                signalMapper.updateById(item);
                log.debug("Discarded unmentioned signal {}", item.getId());
            }
        }
    }

    private void applyCooldown(ProfileSignal signal) {
        LambdaQueryWrapper<ProfileSignalCooldown> query = new LambdaQueryWrapper<ProfileSignalCooldown>()
                .eq(ProfileSignalCooldown::getUserId, signal.getUserId())
                .eq(ProfileSignalCooldown::getCourseId, signal.getCourseId())
                .eq(ProfileSignalCooldown::getSignalKey, signal.getSignalKey());
        ProfileSignalCooldown existing = cooldownMapper.selectOne(query);

        if (existing != null) {
            if (existing.getCooldownUntil() == null) {
                existing.setCooldownUntil(LocalDateTime.now().plusDays(cooldownDays));
                cooldownMapper.updateById(existing);
            }
            return;
        }

        ProfileSignalCooldown cooldown = new ProfileSignalCooldown();
        cooldown.setUserId(signal.getUserId());
        cooldown.setCourseId(signal.getCourseId());
        cooldown.setSignalKey(signal.getSignalKey());
        cooldown.setCooldownUntil(LocalDateTime.now().plusDays(cooldownDays));
        cooldown.setCreatedAt(LocalDateTime.now());
        cooldownMapper.insert(cooldown);
    }
}
