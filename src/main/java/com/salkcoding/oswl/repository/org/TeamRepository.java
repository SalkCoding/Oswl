package com.salkcoding.oswl.repository.org;

import com.salkcoding.oswl.domain.entity.org.Team;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeamRepository extends JpaRepository<Team, Long> {

    List<Team> findAllByOrderByNameAsc();

    Optional<Team> findByName(String name);

    boolean existsByName(String name);

    /** Direct children of a team — used to detach them before the parent is deleted. */
    List<Team> findByParentId(Long parentId);
}
