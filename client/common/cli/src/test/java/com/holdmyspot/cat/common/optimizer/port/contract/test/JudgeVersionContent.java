package com.holdmyspot.cat.common.optimizer.port.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.JudgeProfile;

/**
 * The content that a harness's judge version stands for, in the form whose hash a judge version registry records.
 * <p>
 * The content holds every field of the judge profile and the evidence section that the harness renders for fixed
 * harness events. Each value is preceded by its name and length, so that no change can move text from one field to
 * another without changing the content.
 */
public final class JudgeVersionContent
{
	/**
	 * Prevents instantiation.
	 */
	private JudgeVersionContent()
	{
	}

	/**
	 * Returns the content of a judge.
	 *
	 * @param judge the judge profile
	 * @param evidenceSection the evidence section that the harness renders for fixed harness events
	 * @return the content
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static String of(JudgeProfile judge, String evidenceSection)
	{
		requireThat(judge, "judge").isNotNull();
		requireThat(evidenceSection, "evidenceSection").isNotNull();
		StringBuilder content = new StringBuilder();
		append(content, "model", judge.profile().model());
		append(content, "effort", judge.profile().effort());
		append(content, "prompt", judge.prompt());
		append(content, "rubric", judge.rubric());
		append(content, "decision_rule", judge.decisionRule());
		append(content, "result_schema", judge.resultSchema());
		append(content, "evidence_section", evidenceSection);
		return content.toString();
	}

	/**
	 * Appends one named value.
	 *
	 * @param content the content to append to
	 * @param name the value's name
	 * @param value the value
	 */
	private static void append(StringBuilder content, String name, String value)
	{
		content.append(name).append(' ').append(value.length()).append('\n').append(value).append('\n');
	}
}
