package io.github.cowwoc.cat.common.optimizer.state;

/**
 * Identifies the producer that owns a native call attempt before the scheduler starts it.
 */
public enum ProducerAttemptKind
{
	/** Contract inspection in the selected project. */
	INSPECTION,
	/** Sterile derivation of the inspected contract. */
	DERIVATION,
	/** Validation of a candidate generator. */
	GENERATOR_VALIDATION,
	/** Candidate generation in a verified private worktree. */
	CANDIDATE,
	/** Evaluation of a frozen candidate. */
	EVALUATOR,
	/** Execution of an assigned trial. */
	TRIAL,
	/** Development advice for an unresolved category. */
	ADVISOR
}
