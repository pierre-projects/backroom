package com.backroom.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.backroom.shared.error.ConflictException;
import com.backroom.shared.error.DomainException;
import com.backroom.shared.error.ForbiddenException;
import com.backroom.shared.error.InvalidRequestException;
import com.backroom.shared.error.NotFoundException;
import com.backroom.shared.error.PayloadTooLargeException;
import com.backroom.shared.error.UnauthenticatedException;
import com.backroom.shared.error.UnsupportedMediaTypeException;
import org.junit.jupiter.api.Test;

/** The status, error code and message of each business failure are API contract the web app relies on. */
class ApiExceptionHandlerTests {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void mapsEachBusinessFailureToItsStatus() {
        assertProblem(new InvalidRequestException("Bad value."), 400, "VALIDATION_ERROR", "Bad value.");
        assertProblem(new InvalidRequestException("INVALID_QUANTITY", "Too precise."), 400, "INVALID_QUANTITY", null);
        assertProblem(new UnauthenticatedException(), 401, "UNAUTHENTICATED", "Sign in to continue.");
        assertProblem(new ForbiddenException(), 403, "FORBIDDEN", "Your role does not permit this action.");
        assertProblem(new NotFoundException(), 404, "NOT_FOUND", "The requested resource is unavailable.");
        assertProblem(new ConflictException("STALE_VERSION", "Reload."), 409, "STALE_VERSION", "Reload.");
        assertProblem(new PayloadTooLargeException("PHOTO_TOO_LARGE", "Too big."), 413, "PHOTO_TOO_LARGE", null);
        assertProblem(
                new UnsupportedMediaTypeException("UNSUPPORTED_IMAGE", "JPEG only."), 415, "UNSUPPORTED_IMAGE", null);
    }

    private void assertProblem(DomainException error, int status, String code, String detail) {
        var problem = handler.domain(error);
        assertThat(problem.getStatus()).isEqualTo(status);
        assertThat(problem.getProperties()).containsEntry("errorCode", code);
        if (detail != null) assertThat(problem.getDetail()).isEqualTo(detail);
    }
}
