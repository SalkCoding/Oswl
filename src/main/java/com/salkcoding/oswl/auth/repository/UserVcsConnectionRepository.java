package com.salkcoding.oswl.auth.repository;

import com.salkcoding.oswl.auth.entity.UserVcsConnection;
import com.salkcoding.oswl.auth.enums.VcsProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserVcsConnectionRepository extends JpaRepository<UserVcsConnection, Long> {
    List<UserVcsConnection> findByUserIdAndActiveTrue(Long userId);

    Optional<UserVcsConnection> findByUserIdAndProviderAndActiveTrue(Long userId, VcsProvider provider);
}
