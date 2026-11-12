package com.backroom.access;

import jakarta.validation.*;
import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PasswordValidator.class)
public @interface ValidPassword {
    String message() default "Use at least 12 characters and no more than 72 UTF-8 bytes.";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
