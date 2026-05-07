package com.learnthink.core.service;

/**
 * 邮箱验证码服务接口
 */
public interface EmailVerificationService {
    
    /**
     * 发送验证码到指定邮箱
     * @param email 邮箱地址
     * @return 验证码（用于测试，生产环境不返回）
     */
    String sendVerificationCode(String email);
    
    /**
     * 验证邮箱验证码是否正确
     * @param email 邮箱地址
     * @param code 用户输入的验证码
     * @return 是否验证通过
     */
    boolean verifyCode(String email, String code);
}
