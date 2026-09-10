package com.salkcoding.oswl.service.ingest.parser;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
public record ManifestIndex(Path root, Map<String, List<Path>> byFileName, Map<String, List<Path>> bySuffix) {}
