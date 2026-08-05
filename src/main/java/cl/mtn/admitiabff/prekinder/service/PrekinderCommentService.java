package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.prekinder.crypto.EnvelopeEncryptionService;
import cl.mtn.admitiabff.prekinder.domain.CommentView;
import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import cl.mtn.admitiabff.prekinder.repository.PrekinderCommentRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderCommentService {
    private final PrekinderCommentRepository repository;
    private final EnvelopeEncryptionService encryption;
    private final PrekinderAccessService access;
    private final TransactionTemplate transactions;

    public PrekinderCommentService(PrekinderCommentRepository repository, EnvelopeEncryptionService encryption,
                                   PrekinderAccessService access,
                                   @Qualifier("prekinderTransactionManager") PlatformTransactionManager manager) {
        this.repository = repository;
        this.encryption = encryption;
        this.access = access;
        this.transactions = new TransactionTemplate(manager);
    }

    public MutationResult create(UUID evaluationId, UUID operationId, String content, String requestId) {
        PrekinderActor actor = access.requireActor();
        return transactions.execute(status -> repository.findByOperation(operationId)
            .map(stored -> new MutationResult(toView(stored), stored.operationId(), null, true))
            .orElseGet(() -> {
                UUID commentId = UUID.randomUUID();
                long sequence = repository.nextSequence(evaluationId);
                var encrypted = encryption.encrypt(content, aad(evaluationId, commentId, 1));
                repository.insertComment(commentId, evaluationId, actor.id(), operationId, sequence, encrypted);
                repository.audit(actor.id(), "COMMENT_CREATED", commentId, "SUCCESS", requestId);
                UUID eventId = repository.outbox(evaluationId, sequence, "COMMENT_CREATED");
                var stored = repository.findCurrent(commentId).orElseThrow();
                return new MutationResult(toView(stored), operationId, eventId, false);
            }));
    }

    public MutationResult revise(UUID commentId, UUID operationId, int baseRevision, String content, String requestId) {
        PrekinderActor actor = access.requireActor();
        return transactions.execute(status -> repository.findByOperation(operationId)
            .map(stored -> new MutationResult(toView(stored), operationId, null, true))
            .orElseGet(() -> {
                var current = repository.findCurrent(commentId)
                    .orElseThrow(() -> new IllegalArgumentException("Comentario no encontrado"));
                int revision = repository.nextRevision(commentId);
                boolean conflict = baseRevision != current.currentRevision();
                String stateValue = conflict ? "CONFLICTED" : "CURRENT";
                var encrypted = encryption.encrypt(content, aad(current.evaluationId(), commentId, revision));
                repository.insertRevision(UUID.randomUUID(), commentId, revision, baseRevision, stateValue,
                    actor.id(), operationId, encrypted);
                long sequence = repository.nextSequence(current.evaluationId());
                if (!conflict) repository.advanceCurrent(commentId, revision, "ACTIVE", sequence);
                repository.audit(actor.id(), conflict ? "COMMENT_REVISION_CONFLICTED" : "COMMENT_REVISED",
                    commentId, "SUCCESS", requestId);
                UUID eventId = repository.outbox(current.evaluationId(), sequence,
                    conflict ? "COMMENT_REVISION_CONFLICTED" : "COMMENT_REVISED");
                var result = conflict
                    ? new PrekinderCommentRepository.StoredComment(commentId, current.evaluationId(), actor.id(), operationId,
                        sequence, current.status(), current.currentRevision(), revision, stateValue, encrypted, current.createdAt())
                    : repository.findCurrent(commentId).orElseThrow();
                return new MutationResult(toView(result), operationId, eventId, false);
            }));
    }

    public MutationResult tombstone(UUID commentId, UUID operationId, int baseRevision, String requestId) {
        PrekinderActor actor = access.requireActor();
        return transactions.execute(status -> repository.findByOperation(operationId)
            .map(stored -> new MutationResult(toView(stored), operationId, null, true))
            .orElseGet(() -> {
                var current = repository.findCurrent(commentId)
                    .orElseThrow(() -> new IllegalArgumentException("Comentario no encontrado"));
                if (baseRevision != current.currentRevision()) throw new VersionConflictException("La revisión cambió");
                int revision = repository.nextRevision(commentId);
                var encrypted = encryption.encrypt("", aad(current.evaluationId(), commentId, revision));
                repository.insertRevision(UUID.randomUUID(), commentId, revision, baseRevision, "TOMBSTONE",
                    actor.id(), operationId, encrypted);
                long sequence = repository.nextSequence(current.evaluationId());
                repository.advanceCurrent(commentId, revision, "DELETED", sequence);
                repository.audit(actor.id(), "COMMENT_TOMBSTONED", commentId, "SUCCESS", requestId);
                UUID eventId = repository.outbox(current.evaluationId(), sequence, "COMMENT_TOMBSTONED");
                return new MutationResult(toView(repository.findCurrent(commentId).orElseThrow()), operationId, eventId, false);
            }));
    }

    public List<CommentView> list(UUID evaluationId) {
        access.requireActor();
        return repository.findAll(evaluationId).stream().map(this::toView).toList();
    }

    public List<PrekinderCommentRepository.EventRow> eventsAfter(UUID evaluationId, long after, int limit) {
        access.requireActor();
        return repository.eventsAfter(evaluationId, after, limit);
    }

    private CommentView toView(PrekinderCommentRepository.StoredComment stored) {
        String content = "DELETED".equals(stored.status()) ? "" : encryption.decrypt(stored.encrypted(),
            aad(stored.evaluationId(), stored.commentId(), stored.revision()));
        return new CommentView(stored.commentId(), stored.evaluationId(), stored.authorId(), stored.operationId(),
            stored.sequence(), stored.status(), stored.revision(), stored.revisionState(), content, stored.createdAt());
    }

    private static String aad(UUID evaluationId, UUID commentId, int revision) {
        return "prekinder|comment_revisions|" + commentId + "|evaluation:" + evaluationId + "|revision:" + revision;
    }

    public record MutationResult(CommentView comment, UUID operationId, UUID eventId, boolean duplicate) {}
}
