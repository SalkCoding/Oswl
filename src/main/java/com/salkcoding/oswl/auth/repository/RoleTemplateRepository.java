package com.salkcoding.oswl.auth.repository;

import com.salkcoding.oswl.auth.entity.RoleTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface RoleTemplateRepository extends JpaRepository<RoleTemplate, Long> {
    Optional<RoleTemplate> findByName(String name);

    boolean existsByName(String name);

    @Query("select count(u) from User u join u.roleTemplates rt where rt.id = :templateId")
    long countUsersByTemplateId(Long templateId);
}
