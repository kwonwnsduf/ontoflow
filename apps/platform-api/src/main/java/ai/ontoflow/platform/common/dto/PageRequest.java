package ai.ontoflow.platform.common.dto;

import org.springframework.data.domain.Pageable;

public record PageRequest(int page, int size) {
    public PageRequest {
        if (page < 0 || page > 100000 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Invalid pagination");
        }
    }

    public Pageable toPageable() {
        return org.springframework.data.domain.PageRequest.of(page, size);
    }
}
