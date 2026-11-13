package com.backroom.shared.web;

import com.backroom.shared.error.InvalidRequestException;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
    public static <T> PageResponse<T> from(Page<T> result) {
        return new PageResponse<>(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    public static Pageable request(int page, int size, Sort sort) {
        if (page < 0 || size < 1 || size > 100) {
            throw new InvalidRequestException("Page must be zero or greater; size must be between 1 and 100.");
        }
        return PageRequest.of(page, size, sort);
    }

    public static Pageable named(int page, int size) {
        return request(page, size, Sort.by("name", "id"));
    }
}
