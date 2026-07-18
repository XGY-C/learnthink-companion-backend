package com.learnthink.core.service.impl;

import com.learnthink.common.exception.BusinessException;
import com.learnthink.common.exception.ErrorCode;
import com.learnthink.core.service.EmailVerificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

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
        String html = buildEmailHtml(code);
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, "utf-8");
            helper.setFrom("3929483358@qq.com");
            helper.setTo(to);
            helper.setSubject("【学思伴行】验证您的电子邮箱");
            helper.setText(html, true);
            mailSender.send(mime);
        } catch (MessagingException e) {
            throw new RuntimeException("邮件构建失败", e);
        }
    }

    private String buildEmailHtml(String code) {
        StringBuilder digits = new StringBuilder();
        for (char c : code.toCharArray()) {
            digits.append(String.format(
                "<td style=\"width:52px;height:60px;background:#f7f8fc;border:1px solid #e8ecf4;border-radius:12px;text-align:center;vertical-align:middle;font-size:26px;font-weight:700;color:#1a1a2e;font-family:'SF Mono','Fira Code',Consolas,monospace;padding:0;\">%s</td>",
                c
            ));
        }
        return String.format(
            "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0\"></head>" +
            "<body style=\"margin:0;padding:0;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'PingFang SC','Microsoft YaHei',sans-serif;background:#f5f6fa;\">" +
            "<table border=\"0\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%%\" style=\"padding:40px 0;\"><tr><td align=\"center\">" +
            "<table border=\"0\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:480px;width:100%%;background:#fff;border-radius:20px;box-shadow:0 4px 24px rgba(0,0,0,0.06);\"><tr><td style=\"padding:48px 44px 40px;\">" +
            /* brand */
            "<table border=\"0\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin-bottom:36px;\"><tr><td style=\"width:8px;height:8px;background:#2B6FFF;border-radius:50%;padding:0;\"></td><td style=\"padding:0 0 0 8px;font-size:15px;font-weight:600;color:#1a1a2e;letter-spacing:0.5px;\">学思伴行</td></tr></table>" +
            /* title */
            "<h1 style=\"font-size:22px;font-weight:700;color:#1a1a2e;margin:0 0 16px;\">验证您的电子邮箱</h1>" +
            "<p style=\"font-size:14px;color:#6e6e8a;margin:0 0 6px;\">您好，</p>" +
            "<p style=\"font-size:14px;line-height:1.6;color:#6e6e8a;margin:0 0 32px;\">感谢您使用学思伴行。您正在进行邮箱验证，您的专属验证码为：</p>" +
            /* code digits */
            "<table border=\"0\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:0 auto 24px;\"><tr>%s</tr></table>" +
            "<p style=\"text-align:center;font-size:13px;color:#a0a0b8;line-height:1.6;margin:0 0 2px;\">此验证码将在 <strong style=\"color:#2B6FFF;font-weight:600;\">15 分钟</strong> 后失效。请勿将此验证码转发或泄露给他人。</p>" +
            "<p style=\"text-align:center;font-size:13px;color:#a0a0b8;line-height:1.6;margin:0 0 28px;\">如果这不是您的操作，请忽略此邮件，您的账号依然安全。</p>" +
            /* divider */
            "<table border=\"0\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%%\" style=\"margin-bottom:20px;\"><tr><td style=\"height:1px;background:#f0f1f5;padding:0;\"></td></tr></table>" +
            /* footer */
            "<p style=\"font-size:12px;line-height:1.7;color:#b8b8d0;margin:0;\">学思伴行 © 2026<br>系统自动发信，请勿直接回复。</p>" +
            "</td></tr></table></td></tr></table></body></html>",
            digits
        );
    }
}
