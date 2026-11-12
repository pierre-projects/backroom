package com.backroom.access;
import jakarta.validation.*;
import java.nio.charset.StandardCharsets;
public class PasswordValidator implements ConstraintValidator<ValidPassword, String> {
    public static boolean valid(String value) {
        return value != null && value.codePointCount(0, value.length()) >= 12
                && value.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
    @Override public boolean isValid(String value, ConstraintValidatorContext context) { return valid(value); }
}
