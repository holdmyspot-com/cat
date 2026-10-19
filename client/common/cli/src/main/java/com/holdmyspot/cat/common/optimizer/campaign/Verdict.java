package com.holdmyspot.cat.common.optimizer.campaign;

/**
 * The pass or fail result of one trial, determined by the judge or by failed built-in tools.
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
