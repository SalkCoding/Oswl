package com.salkcoding.oswl.domain.entity;

import static com.querydsl.core.types.PathMetadataFactory.*;

import com.querydsl.core.types.dsl.*;

import com.querydsl.core.types.dsl.StringTemplate;

import com.querydsl.core.types.PathMetadata;
import com.querydsl.core.annotations.Generated;
import com.querydsl.core.types.Path;
import com.querydsl.core.types.dsl.PathInits;


/**
 * QScanResult is a Querydsl query type for ScanResult
 */
@SuppressWarnings("this-escape")
@Generated("com.querydsl.codegen.DefaultEntitySerializer")
public class QScanResult extends EntityPathBase<ScanResult> {

    private static final long serialVersionUID = 1130112518L;

    private static final PathInits INITS = PathInits.DIRECT2;

    public static final QScanResult scanResult = new QScanResult("scanResult");

    public final ListPath<OswlComponent, QOswlComponent> components = this.<OswlComponent, QOswlComponent>createList("components", OswlComponent.class, QOswlComponent.class, PathInits.DIRECT2);

    public final StringPath errorMessage = createString("errorMessage");

    public final NumberPath<Long> id = createNumber("id", Long.class);

    public final QProject project;

    public final StringPath rawPayload = createString("rawPayload");

    public final DateTimePath<java.time.LocalDateTime> scannedAt = createDateTime("scannedAt", java.time.LocalDateTime.class);

    public final EnumPath<com.salkcoding.oswl.domain.enums.ScanStatus> status = createEnum("status", com.salkcoding.oswl.domain.enums.ScanStatus.class);

    public final StringPath version = createString("version");

    public QScanResult(String variable) {
        this(ScanResult.class, forVariable(variable), INITS);
    }

    public QScanResult(Path<? extends ScanResult> path) {
        this(path.getType(), path.getMetadata(), PathInits.getFor(path.getMetadata(), INITS));
    }

    public QScanResult(PathMetadata metadata) {
        this(metadata, PathInits.getFor(metadata, INITS));
    }

    public QScanResult(PathMetadata metadata, PathInits inits) {
        this(ScanResult.class, metadata, inits);
    }

    public QScanResult(Class<? extends ScanResult> type, PathMetadata metadata, PathInits inits) {
        super(type, metadata, inits);
        this.project = inits.isInitialized("project") ? new QProject(forProperty("project")) : null;
    }

}

