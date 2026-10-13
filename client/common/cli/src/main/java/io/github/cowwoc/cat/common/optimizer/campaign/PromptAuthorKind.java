package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;

/**
 * One kind of failure in a prompt author's manifest: the supplied failures whose trials first depart from the contract
 * at the same action, with the prompt author's classification of the kind and the changed hunks that address it.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param name the prompt author's name for the kind
 * @param failures the identifiers of the supplied failures that belong to the kind
 * @param classification the boundary that the prompt author holds responsible for the kind
 * @param hunks the identifiers of the manifest's hunks that address the kind
 * @param fault for a test defect, what in the test causes the kind; otherwise empty
 */
public record PromptAuthorKind(String name, List<String> failures, FailureKind classification, List<String> hunks,
	String fault)
{
	/**
	 * Creates a kind.
	 *
	 * @param name the prompt author's name for the kind
	 * @param failures the identifiers of the supplied failures that belong to the kind
	 * @param classification the boundary that the prompt author holds responsible for the kind
	 * @param hunks the identifiers of the manifest's hunks that address the kind
	 * @param fault for a test defect, what in the test causes the kind; otherwise empty
	 * @throws NullPointerException if any argument or collection element is null
	 * @throws IllegalArgumentException if {@code name}, a failure identifier or a hunk identifier is blank, or
	 *   {@code failures} is empty
	 */
	public PromptAuthorKind
	{
		requireThat(name, "name").isNotBlank();
		requireThat(failures, "failures").isNotEmpty();
		failures = List.copyOf(failures);
		for (String failure : failures)
			requireThat(failure, "failure").isNotBlank();
		requireThat(classification, "classification").isNotNull();
		hunks = List.copyOf(hunks);
		for (String hunk : hunks)
			requireThat(hunk, "hunk").isNotBlank();
		requireThat(fault, "fault").isNotNull();
	}

	/**
	 * Indicates whether a prompt edit can correct this kind.
	 *
	 * @return {@code true} if the kind is classified routing or guidance
	 */
	public boolean isCorrectable()
	{
		return classification == FailureKind.ROUTING || classification == FailureKind.GUIDANCE;
	}
}
