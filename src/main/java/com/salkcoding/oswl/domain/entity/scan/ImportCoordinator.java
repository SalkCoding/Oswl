package com.salkcoding.oswl.domain.entity.scan;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Singleton lock serializes cluster-wide admission and worker slot reservations. */
@Entity
@Table(name = "import_coordinator")
@Getter
@NoArgsConstructor
public class ImportCoordinator {
    @Id private Long id = 1L;
}
