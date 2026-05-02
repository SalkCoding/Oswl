package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

public interface ProjectRepository extends JpaRepository<Project, Long>,
        QuerydslPredicateExecutor<Project> {
}
