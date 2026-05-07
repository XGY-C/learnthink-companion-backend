package com.learnthink.core.service.impl;

import com.learnthink.common.exception.BusinessException;
import com.learnthink.common.exception.ErrorCode;
import com.learnthink.core.service.EmailVerificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 邮箱验证码服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationServiceImpl implements EmailVerificationService {
    
    private final StringRedisTemplate redisTemplate;
    private final JavaMailSender mailSender;
    
    // Redis Key 前缀
    private static final String VERIFICATION_CODE_PREFIX = "verification:code:";
    // 验证码有效期：5分钟
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    // 发送频率限制：1分钟内不能重复发送
    private static final Duration SEND_RATE_LIMIT = Duration.ofMinutes(1);
    // 验证码长度
    private static final int CODE_LENGTH = 6;
    
    @Override
    public String sendVerificationCode(String email) {
        // 1. 验证邮箱格式
        if (!isValidEmail(email)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "邮箱格式不正确");
        }
        
        // 2. 检查发送频率限制
        String rateLimitKey = VERIFICATION_CODE_PREFIX + "rate:" + email;
        Boolean exists = redisTemplate.hasKey(rateLimitKey);
        if (Boolean.TRUE.equals(exists)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "验证码发送过于频繁，请1分钟后再试");
        }
        
        // 3. 生成6位随机验证码
        String code = generateVerificationCode();
        
        // 4. 存储到Redis（5分钟有效期）
        String codeKey = VERIFICATION_CODE_PREFIX + email;
        redisTemplate.opsForValue().set(codeKey, code, CODE_TTL.toSeconds(), TimeUnit.SECONDS);
        
        // 5. 设置发送频率限制（1分钟）
        redisTemplate.opsForValue().set(rateLimitKey, "1", SEND_RATE_LIMIT.toSeconds(), TimeUnit.SECONDS);
        
        // 6. 发送邮件
        try {
            sendEmail(email, code);
            log.info("Verification code sent to email: {}", email);
        } catch (Exception e) {
            log.error("Failed to send verification code email to: {}", email, e);
            // 删除已存储的验证码
            redisTemplate.delete(codeKey);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "邮件发送失败，请稍后重试");
        }
        
        return code; // 生产环境建议不返回验证码
    }
    
    @Override
    public boolean verifyCode(String email, String code) {
        if (email == null || code == null) {
            return false;
        }
        
        String key = VERIFICATION_CODE_PREFIX + email;
        String storedCode = redisTemplate.opsForValue().get(key);
        
        if (storedCode == null) {
            log.warn("Verification code expired or not found for email: {}", email);
            return false;
        }
        
        boolean isValid = storedCode.equals(code);
        
        // 验证成功后删除验证码（防止重放）
        if (isValid) {
            redisTemplate.delete(key);
        }
        
        return isValid;
    }
    
    /**
     * 验证邮箱格式
     */
    private boolean isValidEmail(String email) {
        if (email == null || email.isEmpty()) {
            return false;
        }
        // 简单的邮箱格式验证
        return email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    }
    
    /**
     * 生成随机验证码
     */
    private String generateVerificationCode() {
        SecureRandom secureRandom = new SecureRandom();
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(secureRandom.nextInt(10)); // 0-9
        }
        return code.toString();
    }
    
    /**
     * 发送验证码邮件
     */
    private void sendEmail(String to, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom("3929483358@qq.com"); // 发件人必须与SMTP授权用户一致
        message.setTo(to);
        message.setSubject("【学思伴行】账户注册验证码 - 安全验证");
        message.setText(String.format(
            "尊敬的学思伴行用户：\n\n" +
            "您好！感谢您选择学思伴行（LearnThink Companion）智能学习平台。\n\n" +
            "为了保障您的账户安全，我们正在进行身份验证。请使用以下验证码完成注册流程：\n\n" +
            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
            "         验 证 码：%s          \n" +
            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n" +
            "重要提示：\n" +
            "• 该验证码有效期为 5 分钟，请及时使用\n" +
            "• 请勿将验证码泄露给任何人，包括自称客服的人员\n" +
            "• 如非本人操作，请立即忽略此邮件并联系我们的客服团队\n" +
            "• 本验证码仅用于本次注册验证，不可重复使用\n\n" +
            "如有任何疑问，欢迎随时联系我们：\n" +
            "官方网站：https://www.learnthink.com\n" +
            "客服热线：400-xxx-xxxx\n" +
            "服务时间：工作日 9:00-18:00\n\n" +
            "祝您在学思伴行平台获得愉快的学习体验！\n\n" +
            "此致\n" +
            "敬礼\n\n" +
            "学思伴行（LearnThink Companion）产品团队\n" +
            "智能教育科技事业部\n" +
            "© 2026 LearnThink. All Rights Reserved.",
            code
        ));
        
        mailSender.send(message);
    }
}
