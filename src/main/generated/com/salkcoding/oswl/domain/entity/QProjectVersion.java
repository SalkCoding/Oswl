package com.salkcoding.oswl.domain.entity;

import static com.querydsl.core.types.PathMetadataFactory.*;

import com.querydsl.core.types.dsl.*;

import com.querydsl.core.types.dsl.StringTemplate;

import com.querydsl.core.types.PathMetadata;
import com.querydsl.core.annotations.Generated;
import com.querydsl.core.types.Path;
import com.querydsl.core.types.dsl.PathInits;


/**
 * QProjectVersion is a Querydsl query type for ProjectVersion
 */
@SuppressWarnings("this-escape")
@Generated("com.querydsl.codegen.DefaultEntitySerializer")
public class QProjectVersion extends EntityPathBase<ProjectVersion> {

    private static final long serialVersionUID = -1502068085L;

    private static final PathInits INITS = PathInits.DIRECT2;

    public static final QProjectVersion projectVersion = new QProjectVersion("projectVersion");

    public final StringPath branch = createString("branch");

    public final NumberPath<Long> id = createNumber("id", Long.class);

    public final DateTimePath<java.time.LocalDateTime> importedAt = createDateTime("importedAt", java.time.LocalDateTime.class);

    public final EnumPath<com.salkcoding.oswl.domain.enums.ImportSource> importSource = createEnum("importSource", com.salkcoding.oswl.domain.enums.ImportSource.class);

    public final DateTimePath<java.time.LocalDateTime> lastUpdatedAt = createDateTime("lastUpdatedAt", java.time.LocalDateTime.class);

    public final QProject project;

    public final NumberPath<Integer> versionNumber = createNumber("versionNumber", Integer.class);

    public QProjectVersion(String variable) {
        this(ProjectVersion.class, forVariable(variable), INITS);
    }

    public QProjectVersion(Path<? extends ProjectVersion> path) {
        this(path.getType(), path.getMetadata(), PathInits.getFor(path.getMetadata(), INITS));
    }

    public QProjectVersion(PathMetadata metadata) {
        this(metadata, PathInits.getFor(metadata, INITS));
    }

    public QProjectVersion(PathMetadata metadata, PathInits inits) {
        this(ProjectVersion.class, metadata, inits);
    }

    public QProjectVersion(Class<? extends ProjectVersion> type, PathMetadata metadata, PathInits inits) {
        super(type, metadata, inits);
        this.project = inits.isInitialized("project") ? new QProject(forProperty("project")) : null;
    }

}

