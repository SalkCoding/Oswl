package com.salkcoding.oswl.auth.repository;

import com.salkcoding.oswl.auth.entity.InstanceSetupLock;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstanceSetupLockRepository extends JpaRepository<InstanceSetupLock, Long> {
}
