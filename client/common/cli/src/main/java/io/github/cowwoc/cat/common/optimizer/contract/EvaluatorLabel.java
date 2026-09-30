package io.github.cowwoc.cat.common.optimizer.contract;

/** The frozen evaluator's semantic compliance decision for one assigned trial. */
public enum EvaluatorLabel
{
	/** The observed trial complied under the confirmed rubric. */
	COMPLIANT,
	/** The observed trial did not comply under the confirmed rubric. */
	NONCOMPLIANT
}
