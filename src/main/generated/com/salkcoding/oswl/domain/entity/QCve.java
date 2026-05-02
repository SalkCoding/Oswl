package com.salkcoding.oswl.domain.entity;

import static com.querydsl.core.types.PathMetadataFactory.*;

import com.querydsl.core.types.dsl.*;

import com.querydsl.core.types.dsl.StringTemplate;

import com.querydsl.core.types.PathMetadata;
import com.querydsl.core.annotations.Generated;
import com.querydsl.core.types.Path;
import com.querydsl.core.types.dsl.PathInits;


/**
 * QCve is a Querydsl query type for Cve
 */
@SuppressWarnings("this-escape")
@Generated("com.querydsl.codegen.DefaultEntitySerializer")
public class QCve extends EntityPathBase<Cve> {

    private static final long serialVersionUID = -2041996602L;

    private static final PathInits INITS = PathInits.DIRECT2;

    public static final QCve cve = new QCve("cve");

    public final StringPath affects = createString("affects");

    public final StringPath aiSummary = createString("aiSummary");

    public final QOswlComponent component;

    public final StringPath cveId = createString("cveId");

    public final NumberPath<Double> cvssScore = createNumber("cvssScore", Double.class);

    public final StringPath discoveredOn = createString("discoveredOn");

    public final StringPath fixVersion = createString("fixVersion");

    public final NumberPath<Long> id = createNumber("id", Long.class);

    public final EnumPath<com.salkcoding.oswl.domain.enums.RiskLevel> severity = createEnum("severity", com.salkcoding.oswl.domain.enums.RiskLevel.class);

    public final StringPath type = createString("type");

    public QCve(String variable) {
        this(Cve.class, forVariable(variable), INITS);
    }

    public QCve(Path<? extends Cve> path) {
        this(path.getType(), path.getMetadata(), PathInits.getFor(path.getMetadata(), INITS));
    }

    public QCve(PathMetadata metadata) {
        this(metadata, PathInits.getFor(metadata, INITS));
    }

    public QCve(PathMetadata metadata, PathInits inits) {
        this(Cve.class, metadata, inits);
    }

    public QCve(Class<? extends Cve> type, PathMetadata metadata, PathInits inits) {
        super(type, metadata, inits);
        this.component = inits.isInitialized("component") ? new QOswlComponent(forProperty("component"), inits.get("component")) : null;
    }

}

