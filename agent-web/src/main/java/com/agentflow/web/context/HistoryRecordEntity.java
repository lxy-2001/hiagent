package com.agentflow.web.context;

import com.agentflow.core.context.HistoryStore;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "agent_history_record", uniqueConstraints = @UniqueConstraint(name = "uq_history_record_sequence", columnNames = {"session_id", "record_sequence"}))
public class HistoryRecordEntity {
    @Id @Column(length=36) private String id;
    @Column(name="session_id",length=36,nullable=false) private String sessionId;
    @Column(name="run_id",length=36,nullable=false) private String runId;
    @Column(name="record_sequence",nullable=false) private long recordSequence;
    @Column(length=32,nullable=false) private String kind;
    @Column(length=20) private String role;
    @Column(name="occurred_at",nullable=false) private Instant occurredAt;
    @Column(name="decision_id",length=128) private String decisionId;
    @Column(name="call_id",length=128) private String callId;
    @Column(name="tool_name",length=100) private String toolName;
    @Column(length=64) private String status;
    @Column(name="inline_payload",columnDefinition="longtext") private String inlinePayload;
    @Column(name="payload_bytes",nullable=false) private long payloadBytes;
    @Column(name="artifact_id",length=36) private String artifactId;
    @Column(name="adapter_state_ref",length=36) private String adapterStateRef;
    protected HistoryRecordEntity() { }
    public HistoryRecordEntity(String id, String sessionId, String runId, long sequence, HistoryStore.Item item, String payload, long bytes) {
        this.id=id; this.sessionId=sessionId; this.runId=runId; this.recordSequence=sequence; this.kind=item.kind().name();
        this.role=item.message()==null?null:item.message().role(); this.occurredAt=item.occurredAt(); this.decisionId=item.decisionId();
        this.callId=item.callId(); this.toolName=item.message()==null?null:item.message().name(); this.status=item.status();
        this.inlinePayload=payload; this.payloadBytes=bytes; this.artifactId=item.artifactId(); this.adapterStateRef=item.adapterStateRef();
    }
}
