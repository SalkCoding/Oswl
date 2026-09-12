package com.salkcoding.oswl.dto.scan;

/** Ordered identity projection; loading these rows does not retain component entities. */
public record SecurityCenterRowRef(Long componentId, Long libraryId) {}
