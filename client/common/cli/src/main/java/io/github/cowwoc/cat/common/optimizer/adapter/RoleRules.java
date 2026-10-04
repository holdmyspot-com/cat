package io.github.cowwoc.cat.common.optimizer.adapter;

import io.github.cowwoc.cat.common.ProjectRuleLoader;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Delivers the rules of an optimizer role in the first brief sent by {@link RunnerSessions}.
 */
final class RoleRules
{
	private static final Path CONTRACT_DESIGN = Path.of("include/common/contract-design.md");
	private static final Map<SessionRole, List<Path>> RULES = Map.of(
		SessionRole.AUTHOR, List.of(Path.of("include/common/write-clearly.md"),
			Path.of("include/common/failure-prevention.md"), CONTRACT_DESIGN,
			Path.of("include/common/rule-organization.md"), Path.of("include/common/prompt-file-dependencies.md"),
			Path.of("include/common/prompt-file-references.md")),
		SessionRole.DERIVER, List.of(CONTRACT_DESIGN),
		SessionRole.JUDGE, List.of(),
		SessionRole.TRIAGE, List.of(),
		SessionRole.TRIAL, List.of());
	private static final String SECTION = """


		## Rules

		These rules are in force for this session and are already complete; do not read them again.

		""";
	private static final String PLACEHOLDER = "{{rules}}";
	private static final String TEMPLATE_SECTION = SECTION + PLACEHOLDER + "\n";

	/**
	 * Prevents instantiation.
	 */
	private RoleRules()
	{
	}

	/**
	 * Renders the role's installed rules into the brief's final Rules section, or appends that section.
	 *
	 * @param artifact the installed artifact supplying the role's rules
	 * @param role the session's role
	 * @param prompt the complete first turn
	 * @return the brief with rendered rules, unchanged for a role with no rules
	 * @throws IOException if any required rule cannot be read or rendered
	 */
	static String fill(Path artifact, SessionRole role, String prompt) throws IOException
	{
		List<Path> rules = RULES.get(role);
		if (rules.isEmpty())
			return prompt;

		ProjectRuleLoader loader = new ProjectRuleLoader(YAMLMapper.builder().build());
		List<String> bodies = new ArrayList<>(rules.size());
		for (Path rule : rules)
			bodies.add(loader.loadRule(artifact, rule));
		String rendered = String.join("\n\n---\n\n", bodies);
		if (prompt.endsWith(TEMPLATE_SECTION))
			return prompt.substring(0, prompt.length() - PLACEHOLDER.length() - 1) + rendered + "\n";
		return prompt + SECTION + rendered + "\n";
	}
}
