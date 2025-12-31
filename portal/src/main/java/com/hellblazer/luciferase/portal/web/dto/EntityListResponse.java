package com.hellblazer.luciferase.portal.web.dto;

import java.util.List;

/**
 * Paginated response for entity listing.
 */
public record EntityListResponse(
        List<EntityInfo> entities,
        int page,
        int size,
        int totalCount,
        int totalPages
) {}
