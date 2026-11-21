package com.holdmyspot.cat.common.optimizer.evidence;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.proposal.PromptTemplate;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The template and inputs of an evaluation prompt whose evidence paths depend on where the session runs.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param template the non-blank prompt template
 * @param values the placeholder values to insert, including the evidence section when the template uses it
 * @param transcript the original trial transcript
 * @param effects the original trial effects directory
 */
public record EvidencePrompt(String template, Map<String, String> values, Path transcript, Path effects)
{
	/**
	 * Creates an evaluation prompt with a copy of the supplied values.
	 *
	 * @throws NullPointerException if any argument or placeholder name or value is null
	 * @throws IllegalArgumentException if the template is blank
	 */
	public EvidencePrompt
	{
		requireThat(template, "template").isNotBlank();
		requireThat(transcript, "transcript").isNotNull();
		requireThat(effects, "effects").isNotNull();
		values = Map.copyOf(values);
	}

	/**
	 * Fills the template with paths accessible to the session. The path mapping applies to the dedicated path
	 * placeholders and to the heading of the rendered evidence section.
	 *
	 * @param offered maps an original path to the corresponding path the runner offers
	 * @return the completed prompt
	 * @throws NullPointerException if {@code offered} is null
	 */
	public String render(UnaryOperator<Path> offered)
	{
		requireThat(offered, "offered").isNotNull();
		Path offeredTranscript = offered.apply(transcript);
		Path offeredEffects = offered.apply(effects);
		Map<String, String> staged = new HashMap<>(values);
		staged.computeIfPresent("transcript", (_, _) -> offeredTranscript.toString());
		staged.computeIfPresent("effects", (_, _) -> offeredEffects.toString());
		staged.computeIfPresent("evidence", (_, section) ->
			EvidenceSection.withPaths(section, transcript, effects, offeredTranscript, offeredEffects));
		return new PromptTemplate(template).fill(staged);
	}
}
