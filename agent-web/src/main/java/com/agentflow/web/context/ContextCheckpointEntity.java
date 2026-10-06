package com.agentflow.web.context;

import com.agentflow.core.context.HistoryStore;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="agent_context_checkpoint", uniqueConstraints=@UniqueConstraint(name="uq_checkpoint_revision",columnNames={"session_id","revision"}))
public class ContextCheckpointEntity {
    @Id @Column(length=36) private String id;
    @Column(name="session_id",length=36,nullable=false) private String sessionId;
    @Column(nullable=false) private long revision;
    @Column(name="base_checkpoint_id",length=36) private String baseCheckpointId;
    @Column(name="covered_through_sequence",nullable=false) private long coveredThroughSequence;
    @Column(columnDefinition="longtext",nullable=false) private String summary;
    @Column(name="summary_tokens",nullable=false) private long summaryTokens;
    @Column(name="source_history_tokens",nullable=false) private long sourceHistoryTokens;
    @Column(name="retained_history_tokens",nullable=false) private long retainedHistoryTokens;
    @Column(name="tail_start_sequence",nullable=false) private long tailStartSequence;
    @Column(name="model_request_key",length=128,nullable=false) private String modelRequestKey;
    @Column(name="prompt_version",length=64,nullable=false) private String promptVersion;
    @Column(name="model_profile_hash",length=64,nullable=false) private String modelProfileHash;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    protected ContextCheckpointEntity() { }
    public ContextCheckpointEntity(String sessionId, HistoryStore.Checkpoint checkpoint) {
        this.sessionId=sessionId; id=checkpoint.id(); revision=checkpoint.revision(); baseCheckpointId=checkpoint.baseId();
        coveredThroughSequence=checkpoint.coveredThrough(); summary=checkpoint.summary(); summaryTokens=checkpoint.summaryTokens();
        sourceHistoryTokens=checkpoint.sourceTokens(); retainedHistoryTokens=checkpoint.retainedTokens(); tailStartSequence=checkpoint.tailStart();
        modelRequestKey=checkpoint.requestKey(); promptVersion=checkpoint.promptVersion(); modelProfileHash=checkpoint.profileHash(); createdAt=checkpoint.createdAt();
    }
    public String getSessionId() { return sessionId; }
    public HistoryStore.Checkpoint toCheckpoint() { return new HistoryStore.Checkpoint(id,revision,baseCheckpointId,coveredThroughSequence,summary,summaryTokens,sourceHistoryTokens,retainedHistoryTokens,tailStartSequence,modelRequestKey,promptVersion,modelProfileHash,createdAt); }
}
