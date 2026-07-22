package com.salkcoding.oswl.auth.repository;

import com.salkcoding.oswl.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByIsSystemAdminTrue();

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update User u set u.lastLoginAt = :time where u.email = :email")
    void updateLastLoginAt(@Param("email") String email, @Param("time") LocalDateTime time);

    /** Atomic DB-side increment so concurrent login failures never lose a count. */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update User u set u.loginFailureCount = u.loginFailureCount + 1 where u.email = :email")
    int incrementLoginFailureCount(@Param("email") String email);

    /** Atomic account disable applied when the login failure threshold is crossed. */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update User u set u.enabled = false where u.email = :email")
    int disableByEmail(@Param("email") String email);
}
