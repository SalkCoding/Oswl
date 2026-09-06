package com.salkcoding.oswl.repository.scan;

import com.salkcoding.oswl.domain.entity.scan.ImportCoordinator;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
public interface ImportCoordinatorRepository extends JpaRepository<ImportCoordinator, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ImportCoordinator c WHERE c.id = 1")
    ImportCoordinator lockCoordinator();
}
