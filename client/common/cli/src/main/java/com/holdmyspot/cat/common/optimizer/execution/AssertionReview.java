package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInTool;
import com.holdmyspot.cat.common.optimizer.contract.EvidenceScope;
import com.holdmyspot.cat.common.optimizer.contract.MappedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.OnToolFailure;
import com.holdmyspot.cat.common.optimizer.contract.SchemaValidator;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.proposal.JsonResponse;
import com.holdmyspot.cat.common.optimizer.proposal.PromptTemplate;
import com.holdmyspot.cat.common.optimizer.report.EnumNames;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads the reviewer session's decisions about the evidence scopes and splits of a prompt test's declared assertions.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class AssertionReview
{
	private static final String SCHEMA_RESOURCE = "/optimizer/assertions/assertion-review.schema.json";
	private final JsonResponse responses;
	private final SchemaValidator schema;

	/**
	 * Creates a review result reader.
	 *
	 * @param json the operation-owned JSON mapper
	 * @throws NullPointerException if {@code json} is null
	 * @throws UncheckedIOException if the packaged result schema cannot be read
	 */
	public AssertionReview(JsonMapper json)
	{
		requireThat(json, "json").isNotNull();
		this.responses = new JsonResponse(json);
		this.schema = new SchemaValidator(json.readTree(resultSchema()));
	}

	/**
	 * Returns the schema that the assertion reviewer receives and its result must match.
	 *
	 * @return the schema as JSON text
	 * @throws UncheckedIOException if the packaged result schema cannot be read
	 */
	public static String resultSchema()
	{
		return PromptTemplate.load(SCHEMA_RESOURCE).text();
	}

	/**
	 * Returns the evidence a judge reads for a declared assertion.
	 *
	 * @param assertion the test's assertion
	 * @return the scope, defaulting to actions for a legacy judged assertion, or empty for a mechanical assertion
	 * @throws NullPointerException if {@code assertion} is null
	 */
	public static Optional<EvidenceScope> scopeOf(Assertion assertion)
	{
		requireThat(assertion, "assertion").isNotNull();
		if (assertion.tool() != null && assertion.onToolFailure() == OnToolFailure.FAIL)
			return Optional.empty();
		EvidenceScope scope = assertion.evidenceScope();
		if (scope == null)
			return Optional.of(EvidenceScope.ACTIONS);
		return Optional.of(scope);
	}

	/**
	 * Extracts the findings from a complete review. Each judged assertion must appear exactly once, its decision
	 * must suit its scope, and a tool warning must specify a built-in tool that observes actions.
	 *
	 * @param result the completed reviewer session
	 * @param assertions the test's declared assertions
	 * @return the findings in the reviewer's order, possibly an empty list, or an empty optional for invalid output
	 * @throws NullPointerException if any of the arguments or assertions is null
	 */
	public Optional<List<Finding>> findings(SessionResult.Completed result, List<MappedAssertion> assertions)
	{
		requireThat(result, "result").isNotNull();
		requireThat(assertions, "assertions").isNotNull();
		ObjectNode output = responses.parseFinalResponse(result).orElse(null);
		if (output == null || !schema.violations(output).isEmpty())
			return Optional.empty();

		Map<String, EvidenceScope> remaining = new HashMap<>();
		for (MappedAssertion mapped : assertions)
		{
			Assertion assertion = mapped.assertion();
			scopeOf(assertion).ifPresent(scope -> remaining.put(assertion.id(), scope));
		}
		List<Finding> findings = new ArrayList<>();
		try
		{
			for (JsonNode row : output.get("reviews"))
			{
				String id = row.get("assertion_id").asString();
				EvidenceScope scope = remaining.remove(id);
				if (scope == null)
					return Optional.empty();
				String decision = row.get("kind").asString();
				JsonNode tool = row.get("tool");
				if (decision.equals("no_finding"))
				{
					if (!tool.isNull())
						return Optional.empty();
					continue;
				}
				Kind kind = EnumNames.parse(decision, Kind.class);
				boolean namesTool = !tool.isNull();
				if (kind.scope != scope || namesTool != (kind == Kind.ACTION_DECIDABLE_BY_TOOL))
					return Optional.empty();
				String toolName = null;
				if (namesTool)
				{
					BuiltInTool parsed = BuiltInTool.parse(tool.asString());
					if (parsed.examinesOnlyReplies())
						return Optional.empty();
					toolName = parsed.name();
				}
				findings.add(new Finding(id, kind, row.get("rationale").asString(), toolName));
			}
		}
		catch (IllegalArgumentException _)
		{
			return Optional.empty();
		}
		if (!remaining.isEmpty())
			return Optional.empty();
		return Optional.of(List.copyOf(findings));
	}

	/**
	 * A finding that the proposal can report beside an assertion.
	 *
	 * @param assertionId the declared assertion that the reviewer identified
	 * @param kind the type of finding
	 * @param rationale why the reviewer made the finding
	 * @param tool the name of the built-in tool for {@link Kind#ACTION_DECIDABLE_BY_TOOL}, otherwise {@code null}
	 */
	public record Finding(String assertionId, Kind kind, String rationale, String tool)
	{
		/**
		 * Creates a finding.
		 *
		 * @param assertionId the declared assertion that the reviewer identified
		 * @param kind the type of finding
		 * @param rationale why the reviewer made the finding
		 * @param tool the name of the built-in tool for {@link Kind#ACTION_DECIDABLE_BY_TOOL}, otherwise {@code null}
		 * @throws NullPointerException if {@code assertionId}, {@code kind}, or {@code rationale} is null
		 * @throws IllegalArgumentException if a supplied string is blank or {@code tool} does not suit the kind
		 */
		public Finding
		{
			requireThat(assertionId, "assertionId").isNotBlank();
			requireThat(kind, "kind").isNotNull();
			requireThat(rationale, "rationale").isNotBlank();
			if ((kind == Kind.ACTION_DECIDABLE_BY_TOOL) != (tool != null))
				throw new IllegalArgumentException("Only a finding that an action is decidable by a tool names a tool");
			if (tool != null)
				requireThat(tool, "tool").isNotBlank();
		}
	}

	/**
	 * The findings an assertion reviewer can make.
	 */
	public enum Kind
	{
		/**
		 * A response judge lacks evidence about the agent's actions.
		 */
		RESPONSE_DEPENDS_ON_ACTIONS(EvidenceScope.RESPONSE),
		/**
		 * An actions judge could decide the assertion from the replies.
		 */
		ACTIONS_DEPENDS_ONLY_ON_REPLIES(EvidenceScope.ACTIONS),
		/**
		 * A CAT built-in tool can decide whether the action happened.
		 */
		ACTION_DECIDABLE_BY_TOOL(EvidenceScope.ACTIONS);

		private final EvidenceScope scope;

		/**
		 * Creates a kind of finding.
		 *
		 * @param scope the scope of the assertion to which the finding applies
		 */
		Kind(EvidenceScope scope)
		{
			this.scope = scope;
		}

		/**
		 * Indicates whether the finding refuses the assertion.
		 *
		 * @return {@code true} when the judge would lack evidence that the assertion needs
		 */
		public boolean refuses()
		{
			return this == RESPONSE_DEPENDS_ON_ACTIONS;
		}
	}
}
