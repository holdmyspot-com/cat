package io.github.cowwoc.cat.common.optimizer.campaign;

/**
 * Where an unadopted logical trial is in its life.
 */
public enum TrialState
{
	/**
	 * The assignment is recorded and no session is running it.
	 */
	ASSIGNED,
	/**
	 * A session is running the trial.
	 */
	RUNNING,
	/**
	 * The judge labelled the trial, and the outcome waits for every earlier trial of its group to be adopted.
	 */
	JUDGED
}
