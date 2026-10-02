package io.github.cowwoc.cat.common.optimizer.campaign;

/**
 * The compliance result of one trial, determined by the judge or by failed deterministic checks.
 */
public enum Verdict
{
	/**
	 * The trial complied with the rubric.
	 */
	PASS,
	/**
	 * The trial did not comply with the rubric.
	 */
	FAIL
}
