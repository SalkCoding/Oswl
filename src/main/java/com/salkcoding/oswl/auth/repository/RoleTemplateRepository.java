package com.salkcoding.oswl.auth.repository;

import com.salkcoding.oswl.auth.entity.RoleTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface RoleTemplateRepository extends JpaRepository<RoleTemplate, Long> {
    boolean existsByName(String name);

    @Query("select count(u) from User u join u.roleTemplates rt where rt.id = :templateId")
    long countUsersByTemplateId(Long templateId);

    @Query("select u.id from User u join u.roleTemplates rt where rt.id = :templateId")
    List<Long> findUserIdsByTemplateId(Long templateId);

    @Modifying
    @Query("update User u set u.enabled = false where u.id in :userIds")
    void deactivateUsers(List<Long> userIds);
}
