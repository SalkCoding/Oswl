package com.salkcoding.oswl.domain.entity.system;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Stable rows used to serialize bounded mutations across application instances. */
@Entity
@Table(name = "database_mutation_locks")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DatabaseMutationLock {
    @Id private Long id;
}
