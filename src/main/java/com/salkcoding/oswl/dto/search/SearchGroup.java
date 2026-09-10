package com.salkcoding.oswl.dto.search;

import java.util.List;

/**
 * One capped result group of the global search response.
 * {@code hasMore} is true when more matches exist beyond the returned {@code items}.
 */
public record SearchGroup<T>(
        List<T> items,
        boolean hasMore
) {

    public static <T> SearchGroup<T> of(List<T> items, boolean hasMore) {
        return new SearchGroup<>(items, hasMore);
    }

    public static <T> SearchGroup<T> empty() {
        return new SearchGroup<>(List.of(), false);
    }
}
