package io.github.cowwoc.cat.common.optimizer.candidate;

/** Durable per-path transaction phase, including publication and restore alias boundaries. */
public enum DeliveryPathPhase
{
	PLANNED,
	STAGED,
	PREDECESSOR_ABSENT,
	PREDECESSOR_CAPTURED,
	SELECTED_ABSENT,
	SELECTED_LINKED,
	SELECTED_DEALIASED,
	PATH_APPLIED,
	ROLLBACK_TARGET_ABSENT,
	ROLLBACK_CAPTURED,
	CONCURRENT_EDIT_PRESERVED,
	RESTORE_ABSENT,
	RESTORE_LINKED,
	RESTORED_DEALIASED,
	PATH_ROLLED_BACK,
	RECOVERY_REQUIRED
}
