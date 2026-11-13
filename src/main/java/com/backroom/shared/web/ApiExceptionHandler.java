package com.backroom.shared.web;

import com.backroom.shared.error.ConflictException;
import com.backroom.shared.error.DomainException;
import com.backroom.shared.error.ForbiddenException;
import com.backroom.shared.error.InvalidRequestException;
import com.backroom.shared.error.NotFoundException;
import com.backroom.shared.error.PayloadTooLargeException;
import com.backroom.shared.error.UnauthenticatedException;
import com.backroom.shared.error.UnsupportedMediaTypeException;
import java.util.LinkedHashMap;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {
    @ExceptionHandler({
        org.springframework.web.HttpRequestMethodNotSupportedException.class,
        org.springframework.web.HttpMediaTypeNotSupportedException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class
    })
    ProblemDetail frameworkRequest(Exception error) {
        var problem = ((org.springframework.web.ErrorResponse) error).getBody();
        problem.setProperty("errorCode", "REQUEST_ERROR");
        return problem;
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception error) {
        log.error("An unexpected application failure occurred", error);
        var problem = ProblemResponses.problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "The action could not be completed. Try again shortly.");
        // Lets a person quote the failure; the same id is on every log line of the request.
        var requestId = RequestIdFilter.current();
        if (requestId != null) problem.setProperty("requestId", requestId);
        return problem;
    }

    /** The one place a business failure becomes an HTTP status. */
    @ExceptionHandler(DomainException.class)
    ProblemDetail domain(DomainException error) {
        return ProblemResponses.problem(statusOf(error), error.errorCode(), error.getMessage());
    }

    static HttpStatus statusOf(DomainException error) {
        return switch (error) {
            case InvalidRequestException ignored -> HttpStatus.BAD_REQUEST;
            case UnauthenticatedException ignored -> HttpStatus.UNAUTHORIZED;
            case ForbiddenException ignored -> HttpStatus.FORBIDDEN;
            case NotFoundException ignored -> HttpStatus.NOT_FOUND;
            case ConflictException ignored -> HttpStatus.CONFLICT;
            case PayloadTooLargeException ignored -> HttpStatus.valueOf(413);
            case UnsupportedMediaTypeException ignored -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            default ->
                throw new IllegalStateException(
                        "No HTTP status mapped for " + error.getClass().getName());
        };
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException error) {
        var result =
                ProblemResponses.problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Check the highlighted fields.");
        var fields = new LinkedHashMap<String, String>();
        error.getBindingResult()
                .getFieldErrors()
                .forEach(field -> fields.putIfAbsent(
                        field.getField(), Objects.toString(field.getDefaultMessage(), "Invalid value")));
        result.setProperty("fieldErrors", fields);
        return result;
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ProblemDetail invalidInput(Exception error) {
        return ProblemResponses.problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "A request value is invalid.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail constraint(DataIntegrityViolationException error) {
        return ProblemResponses.problem(
                HttpStatus.CONFLICT, "CONFLICT", "This change conflicts with an existing record.");
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
    ProblemDetail concurrentChange(Exception error) {
        return ProblemResponses.problem(
                HttpStatus.CONFLICT, "CONCURRENT_CHANGE", "This record changed. Reload and try again.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail missing(NoResourceFoundException error) {
        return ProblemResponses.problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "The requested resource is unavailable.");
    }
}
