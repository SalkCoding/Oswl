package com.salkcoding.oswl.domain.entity;

import static com.querydsl.core.types.PathMetadataFactory.*;

import com.querydsl.core.types.dsl.*;

import com.querydsl.core.types.dsl.StringTemplate;

import com.querydsl.core.types.PathMetadata;
import com.querydsl.core.annotations.Generated;
import com.querydsl.core.types.Path;
import com.querydsl.core.types.dsl.PathInits;


/**
 * QProject is a Querydsl query type for Project
 */
@SuppressWarnings("this-escape")
@Generated("com.querydsl.codegen.DefaultEntitySerializer")
public class QProject extends EntityPathBase<Project> {

    private static final long serialVersionUID = 1752107405L;

    public static final QProject project = new QProject("project");

    public final ListPath<ApiKey, QApiKey> apiKeys = this.<ApiKey, QApiKey>createList("apiKeys", ApiKey.class, QApiKey.class, PathInits.DIRECT2);

    public final DateTimePath<java.time.LocalDateTime> createdAt = createDateTime("createdAt", java.time.LocalDateTime.class);

    public final StringPath githubRepo = createString("githubRepo");

    public final NumberPath<Long> id = createNumber("id", Long.class);

    public final DateTimePath<java.time.LocalDateTime> importedAt = createDateTime("importedAt", java.time.LocalDateTime.class);

    public final DateTimePath<java.time.LocalDateTime> lastScannedAt = createDateTime("lastScannedAt", java.time.LocalDateTime.class);

    public final StringPath name = createString("name");

    public final ListPath<ScanResult, QScanResult> scanResults = this.<ScanResult, QScanResult>createList("scanResults", ScanResult.class, QScanResult.class, PathInits.DIRECT2);

    public final DateTimePath<java.time.LocalDateTime> updatedAt = createDateTime("updatedAt", java.time.LocalDateTime.class);

    public final StringPath version = createString("version");

    public QProject(String variable) {
        super(Project.class, forVariable(variable));
    }

    public QProject(Path<? extends Project> path) {
        super(path.getType(), path.getMetadata());
    }

    public QProject(PathMetadata metadata) {
        super(Project.class, metadata);
    }

}

