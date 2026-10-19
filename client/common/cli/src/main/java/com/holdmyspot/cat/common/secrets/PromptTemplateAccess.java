package com.holdmyspot.cat.common.secrets;


/**
 * Exposes how prompt templates read the files that CAT ships to the module's tests.
 */
@FunctionalInterface
public interface PromptTemplateAccess
{
	/**
	 * Returns the text of a file that CAT ships.
	 *
	 * @param resource the absolute classpath location of the file
	 * @return the text
	 * @throws NullPointerException if {@code resource} is null
	 * @throws java.io.UncheckedIOException if the packaged file is missing or cannot be read
	 */
	String readResource(String resource);
}
