package com.salkcoding.oswl.domain.entity;

import static com.querydsl.core.types.PathMetadataFactory.*;

import com.querydsl.core.types.dsl.*;

import com.querydsl.core.types.dsl.StringTemplate;

import com.querydsl.core.types.PathMetadata;
import com.querydsl.core.annotations.Generated;
import com.querydsl.core.types.Path;
import com.querydsl.core.types.dsl.PathInits;


/**
 * QOswlComponent is a Querydsl query type for OswlComponent
 */
@SuppressWarnings("this-escape")
@Generated("com.querydsl.codegen.DefaultEntitySerializer")
public class QOswlComponent extends EntityPathBase<OswlComponent> {

    private static final long serialVersionUID = 2106867896L;

    private static final PathInits INITS = PathInits.DIRECT2;

    public static final QOswlComponent oswlComponent = new QOswlComponent("oswlComponent");

    public final StringPath aiLicenseSummary = createString("aiLicenseSummary");

    public final ListPath<Cve, QCve> cves = this.<Cve, QCve>createList("cves", Cve.class, QCve.class, PathInits.DIRECT2);

    public final StringPath dependencyInfo = createString("dependencyInfo");

    public final NumberPath<Long> id = createNumber("id", Long.class);

    public final StringPath licenseName = createString("licenseName");

    public final EnumPath<com.salkcoding.oswl.domain.enums.LicenseStatus> licenseStatus = createEnum("licenseStatus", com.salkcoding.oswl.domain.enums.LicenseStatus.class);

    public final StringPath name = createString("name");

    public final EnumPath<com.salkcoding.oswl.domain.enums.Patchability> patchability = createEnum("patchability", com.salkcoding.oswl.domain.enums.Patchability.class);

    public final BooleanPath reviewed = createBoolean("reviewed");

    public final QScanResult scanResult;

    public final StringPath version = createString("version");

    public QOswlComponent(String variable) {
        this(OswlComponent.class, forVariable(variable), INITS);
    }

    public QOswlComponent(Path<? extends OswlComponent> path) {
        this(path.getType(), path.getMetadata(), PathInits.getFor(path.getMetadata(), INITS));
    }

    public QOswlComponent(PathMetadata metadata) {
        this(metadata, PathInits.getFor(metadata, INITS));
    }

    public QOswlComponent(PathMetadata metadata, PathInits inits) {
        this(OswlComponent.class, metadata, inits);
    }

    public QOswlComponent(Class<? extends OswlComponent> type, PathMetadata metadata, PathInits inits) {
        super(type, metadata, inits);
        this.scanResult = inits.isInitialized("scanResult") ? new QScanResult(forProperty("scanResult"), inits.get("scanResult")) : null;
    }

}

