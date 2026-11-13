/**
 * Business failures services throw, free of HTTP types. Each subclass names an outcome; only
 * {@code shared.web.ApiExceptionHandler} decides which HTTP status it becomes.
 */
@NamedInterface("error")
package com.backroom.shared.error;

import org.springframework.modulith.NamedInterface;
