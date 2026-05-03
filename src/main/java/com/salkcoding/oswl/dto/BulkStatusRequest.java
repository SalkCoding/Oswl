package com.salkcoding.oswl.dto;

import java.util.List;

public record BulkStatusRequest(List<Long> ids, Boolean reviewed, Boolean ignored) {}
