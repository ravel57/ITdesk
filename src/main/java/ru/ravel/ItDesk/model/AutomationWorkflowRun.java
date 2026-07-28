package ru.ravel.ItDesk.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.ravel.ItDesk.model.automatosation.AutomationWorkflowRunStatus;
import ru.ravel.ItDesk.model.automatosation.TriggerType;

import java.time.Instant;


@Entity
@Table(
		name = "automation_workflow_run",
		indexes = {
				@Index(name = "idx_automation_workflow_run_due", columnList = "status, available_at"),
				@Index(name = "idx_automation_workflow_run_trigger", columnList = "trigger_id, created_at")
		}
)
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AutomationWorkflowRun {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "trigger_id", nullable = false)
	private Long triggerId;

	@Column(name = "source_event_id")
	private Long sourceEventId;

	@Enumerated(EnumType.STRING)
	@Column(name = "trigger_type", nullable = false)
	private TriggerType triggerType;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "event_payload", columnDefinition = "jsonb", nullable = false)
	private JsonNode eventPayload;

	@Column(name = "workflow_definition", columnDefinition = "text")
	private String workflowDefinition;

	@Column(name = "current_node_id", nullable = false)
	private String currentNodeId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	@Builder.Default
	private AutomationWorkflowRunStatus status = AutomationWorkflowRunStatus.NEW;

	@Column(name = "step_count", nullable = false)
	@Builder.Default
	private Integer stepCount = 0;

	@Column(nullable = false)
	@Builder.Default
	private Integer retries = 0;

	@Column(name = "available_at", nullable = false)
	private Instant availableAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "last_error", length = 2000)
	private String lastError;

	@Column(name = "actor_user_id")
	private Long actorUserId;

	@Column(name = "actor_username")
	private String actorUsername;

	@Column(name = "actor_display_name")
	private String actorDisplayName;

	@Enumerated(EnumType.STRING)
	@Column(name = "actor_type")
	private ActorType actorType;

	@Column(name = "wait_kind", length = 40)
	private String waitKind;

	@Enumerated(EnumType.STRING)
	@Column(name = "wait_event_type", length = 100)
	private TriggerType waitEventType;

	@Column(name = "correlation_key", length = 500)
	private String correlationKey;

	@Column(name = "resume_node_id")
	private String resumeNodeId;

	@Column(name = "rejected_node_id")
	private String rejectedNodeId;

	@Column(name = "timeout_node_id")
	private String timeoutNodeId;

	@Column(name = "wait_expression", columnDefinition = "text")
	private String waitExpression;

	@Column(name = "approver_user_id")
	private Long approverUserId;

	@Column(name = "approval_title", length = 500)
	private String approvalTitle;

	@Column(name = "approval_message", columnDefinition = "text")
	private String approvalMessage;

	@Column(name = "decision_comment", columnDefinition = "text")
	private String decisionComment;


	@PrePersist
	void prePersist() {
		Instant now = Instant.now();
		if (status == null) {
			status = AutomationWorkflowRunStatus.NEW;
		}
		if (stepCount == null) {
			stepCount = 0;
		}
		if (retries == null) {
			retries = 0;
		}
		if (availableAt == null) {
			availableAt = now;
		}
		if (createdAt == null) {
			createdAt = now;
		}
		updatedAt = now;
	}


	@PreUpdate
	void preUpdate() {
		updatedAt = Instant.now();
	}
}