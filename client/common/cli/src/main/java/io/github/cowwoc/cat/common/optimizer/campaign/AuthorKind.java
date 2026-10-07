package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;

/**
 * One kind of failure in an author's manifest: the supplied failures whose trials first depart from the contract at the
 * same action, with the author's classification of the kind and the changed hunks that address it.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param name the author's name for the kind
 * @param instances the identifiers of the supplied failures that belong to the kind
 * @param classification the boundary that the author holds responsible for the kind
 * @param hunks the identifiers of the manifest's hunks that address the kind
 */
public record AuthorKind(String name, List<String> instances, FailureKind classification, List<String> hunks)
{
	/**
	 * Creates a kind.
	 *
	 * @param name the author's name for the kind
	 * @param instances the identifiers of the supplied failures that belong to the kind
	 * @param classification the boundary that the author holds responsible for the kind
	 * @param hunks the identifiers of the manifest's hunks that address the kind
	 * @throws NullPointerException if any argument or collection element is null
	 * @throws IllegalArgumentException if {@code name}, a failure identifier or a hunk identifier is blank, or
	 *   {@code instances} is empty
	 */
	public AuthorKind
	{
		requireThat(name, "name").isNotBlank();
		requireThat(instances, "instances").isNotEmpty();
		instances = List.copyOf(instances);
		for (String instance : instances)
			requireThat(instance, "instance").isNotBlank();
		requireThat(classification, "classification").isNotNull();
		hunks = List.copyOf(hunks);
		for (String hunk : hunks)
			requireThat(hunk, "hunk").isNotBlank();
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
