package io.github.cowwoc.cat.common.optimizer.campaign;

/**
 * The execution state of a trial whose outcome is not yet included in campaign evidence.
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
	 * The trial has a compliance result and waits for earlier trials with the same cell, purpose, snapshot and series
	 * to enter campaign evidence first.
	 */
	JUDGED
}
