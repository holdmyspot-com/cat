package com.holdmyspot.cat.common.fakes.test;

import com.holdmyspot.cat.common.AbstractJvmScope;
import com.holdmyspot.cat.common.JvmScope;
import java.nio.file.Path;

/**
 * Supplies {@link JvmScope} values with the build's temporary directory for tests.
 */
public final class TestJvmScope extends AbstractJvmScope
{
	/**
	 * Creates a scope from the build's {@code cat.session.temp} property, read once for this scope.
	 */
	public TestJvmScope()
	{
		this(Path.of(System.getProperty("cat.session.temp")));
	}

	/**
	 * Creates a scope with a test-owned temporary directory.
	 *
	 * @param temporaryDirectory the directory for temporary files
	 * @throws NullPointerException if {@code temporaryDirectory} is null
	 */
	public TestJvmScope(Path temporaryDirectory)
	{
		super(temporaryDirectory);
	}
}
