package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.JudgeProfile;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;

import java.io.UncheckedIOException;
import java.util.List;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The fields of every judge profile that CAT supplies: the model and effort used by deriver, judge and author
 * sessions (the role profile), the judge prompt and the result schema.
 * <p>
 * The deriver proposes only the rubric and the decision rule. CAT adds its own fields before it validates the
 * proposal, so a prompt system cannot choose how it is judged, and confirmation freezes the complete profile in the
 * contract (design &sect;6.4).
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class JudgeTemplate
{
	/**
	 * The names of the {@code judge} properties that CAT supplies.
	 */
	static final List<String> OWNED_FIELDS = List.of("model", "effort", "prompt", "result_schema");
	private static final String PROMPT_RESOURCE = "/optimizer/judge/judge.md";
	private static final String SCHEMA_RESOURCE = "/optimizer/judge/verdict.schema.json";
	private final JsonMapper json;
	private final TrialProfile role;
	private final String prompt;
	private final String resultSchema;

	/**
	 * Creates a template.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param role the harness's role profile, which the deriver, judge and author sessions run under
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code role} binds nothing
	 * @throws UncheckedIOException if the packaged judge prompt or result schema cannot be read
	 */
	public JudgeTemplate(JsonMapper json, TrialProfile role)
	{
		requireThat(json, "json").isNotNull();
		requireThat(role, "role").isNotNull();
		requireThat(role.isBound(), "role.isBound()").isTrue();
		this.json = json;
		this.role = role;
		this.prompt = PromptTemplate.readResource(PROMPT_RESOURCE);
		// The compact form is the form that a parsed contract holds, so a profile equals the confirmed one
		this.resultSchema = json.readTree(PromptTemplate.readResource(SCHEMA_RESOURCE)).toString();
	}

	/**
	 * Returns the judge profile for a rubric and a decision rule.
	 *
	 * @param rubric the semantic criteria that the judge applies
	 * @param decisionRule when a trial passes and when it fails
	 * @return the profile
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if any of the arguments are blank
	 */
	public JudgeProfile profile(String rubric, String decisionRule)
	{
		requireThat(rubric, "rubric").isNotBlank();
		requireThat(decisionRule, "decisionRule").isNotBlank();
		return new JudgeProfile(role, prompt, rubric, decisionRule, resultSchema);
	}

	/**
	 * Returns the role profile.
	 *
	 * @return the profile that the deriver, judge and author sessions run under
	 */
	TrialProfile role()
	{
		return role;
	}

	/**
	 * Adds CAT's fields to the {@code judge} object of a proposal.
	 *
	 * @param judge the {@code judge} object, which holds none of CAT's fields
	 */
	void complete(ObjectNode judge)
	{
		judge.put("model", role.model());
		judge.put("effort", role.effort());
		judge.put("prompt", prompt);
		judge.set("result_schema", json.readTree(resultSchema));
	}
}
