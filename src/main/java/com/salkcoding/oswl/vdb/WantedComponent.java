package com.salkcoding.oswl.vdb;

/** One (ecosystem, name, version) triple from a wanted-list, as fed to {@code oswl-vdb build --wanted}. */
public record WantedComponent(String ecosystem, String name, String version) {
}
