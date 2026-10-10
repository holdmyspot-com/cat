package io.github.cowwoc.cat.common.secrets;


/**
 * Exposes the diagnostic limit of a harness's standard-error stream to the module's tests.
 */
@FunctionalInterface
public interface HarnessStandardErrorAccess
{
	/**
	 * Returns the number of standard-error bytes retained as a diagnostic.
	 *
	 * @return the number of bytes
	 */
	int maximumDiagnosticBytes();
}
