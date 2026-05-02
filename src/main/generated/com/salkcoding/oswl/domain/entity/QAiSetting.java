package com.salkcoding.oswl.domain.entity;

import static com.querydsl.core.types.PathMetadataFactory.*;

import com.querydsl.core.types.dsl.*;

import com.querydsl.core.types.dsl.StringTemplate;

import com.querydsl.core.types.PathMetadata;
import com.querydsl.core.annotations.Generated;
import com.querydsl.core.types.Path;


/**
 * QAiSetting is a Querydsl query type for AiSetting
 */
@SuppressWarnings("this-escape")
@Generated("com.querydsl.codegen.DefaultEntitySerializer")
public class QAiSetting extends EntityPathBase<AiSetting> {

    private static final long serialVersionUID = -519434628L;

    public static final QAiSetting aiSetting = new QAiSetting("aiSetting");

    public final BooleanPath active = createBoolean("active");

    public final StringPath apiKey = createString("apiKey");

    public final StringPath baseUrl = createString("baseUrl");

    public final DateTimePath<java.time.LocalDateTime> createdAt = createDateTime("createdAt", java.time.LocalDateTime.class);

    public final NumberPath<Long> id = createNumber("id", Long.class);

    public final StringPath modelName = createString("modelName");

    public final EnumPath<com.salkcoding.oswl.domain.enums.AiProvider> provider = createEnum("provider", com.salkcoding.oswl.domain.enums.AiProvider.class);

    public final DateTimePath<java.time.LocalDateTime> updatedAt = createDateTime("updatedAt", java.time.LocalDateTime.class);

    public QAiSetting(String variable) {
        super(AiSetting.class, forVariable(variable));
    }

    public QAiSetting(Path<? extends AiSetting> path) {
        super(path.getType(), path.getMetadata());
    }

    public QAiSetting(PathMetadata metadata) {
        super(AiSetting.class, metadata);
    }

}

