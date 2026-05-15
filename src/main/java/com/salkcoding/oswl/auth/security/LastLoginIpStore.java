package com.salkcoding.oswl.auth.security;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store that tracks the most recent login IP per user (by email).
 * Used to show the displacing IP when a session is forcibly expired
 * due to a concurrent login from another location.
 */
@Component
public class LastLoginIpStore {

    private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

    public void put(String email, String ip) {
        store.put(email, ip);
    }

    public String get(String email) {
        return store.get(email);
    }
}
