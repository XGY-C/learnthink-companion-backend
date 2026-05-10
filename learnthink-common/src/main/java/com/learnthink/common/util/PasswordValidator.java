package com.learnthink.common.util;

import com.learnthink.common.exception.BusinessException;
import com.learnthink.common.exception.ErrorCode;

/**
 * 密码验证工具类
 */
public class PasswordValidator {
    
    /**
     * 验证密码强度
     * 要求：至少8个字符，包含大写字母、小写字母和数字
     * 
     * @param password 待验证的密码
     * @throws BusinessException 如果密码不符合要求
     */
    public static void validate(String password) {
        if (password == null || password.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "密码不能为空");
        }
        
        if (password.length() < 8) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "密码长度至少为8个字符");
        }
        
        boolean hasUpperCase = false;
        boolean hasLowerCase = false;
        boolean hasDigit = false;
        
        for (char c : password.toCharArray()) {
            if (Character.isUpperCase(c)) {
                hasUpperCase = true;
            } else if (Character.isLowerCase(c)) {
                hasLowerCase = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
            
            // 如果所有条件都满足，提前退出
            if (hasUpperCase && hasLowerCase && hasDigit) {
                break;
            }
        }
        
        if (!hasUpperCase) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "密码必须包含至少一个大写字母");
        }
        
        if (!hasLowerCase) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "密码必须包含至少一个小写字母");
        }
        
        if (!hasDigit) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "密码必须包含至少一个数字");
        }
    }
}
