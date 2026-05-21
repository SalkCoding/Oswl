package com.salkcoding.oswl.auth.security;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 사용자(email 기준)별 최근 로그인 IP를 추적하는 인메모리 저장소.
 * 다른 위치에서 동시 로그인으로 세션이 강제 만료될 때
 * 밀려난 사용자에게 표시할 IP를 제공하기 위해 사용된다.
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
