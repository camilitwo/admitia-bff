package cl.mtn.admitiabff.prekinder.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CommentView(
    UUID commentId,
    UUID evaluationId,
    UUID authorId,
    UUID operationId,
    long serverSequence,
    String status,
    int revision,
    String revisionState,
    String content,
    OffsetDateTime createdAt
) {}
