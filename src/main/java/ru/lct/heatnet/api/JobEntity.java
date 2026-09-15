package ru.lct.heatnet.api;

import java.time.Instant;
import java.util.UUID;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnTransformer;

@Entity
@Table(name = "jobs")
@Getter
@Setter
public class JobEntity {
    public enum Status { QUEUED, RUNNING, DONE, FAILED }

    @Id
    private UUID id;
    @Enumerated(EnumType.STRING)
    private Status status;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;
    // JSON хранится строкой: в Hibernate 5.6 нет типа jsonb, приведение делает сама база.
    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String error;
    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String summary;
    private String inputPath;
    private String outputPath;
}
