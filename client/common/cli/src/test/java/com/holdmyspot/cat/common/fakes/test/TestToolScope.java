package com.holdmyspot.cat.common.fakes.test;

import com.holdmyspot.cat.common.AbstractToolScope;
import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.ToolScope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Supplies {@link ToolScope} services without a session. The build's temporary directory holds its temporary files
 * and is the directory from which the tool is invoked, so the scope never depends on the current directory.
 */
public final class TestToolScope extends AbstractToolScope
{
	/**
	 * Creates an open test tool scope.
	 *
	 * @throws UncheckedIOException if the build's temporary directory cannot be created
	 */
	public TestToolScope()
	{
		this(new TestJvmScope());
	}

	/**
	 * Creates an open test tool scope that owns a process scope.
	 *
	 * @param parent the process scope
	 * @throws UncheckedIOException if the process scope's temporary directory cannot be created
	 */
	private TestToolScope(JvmScope parent)
	{
		super(parent, temporaryDirectory(parent), Map.of(), List.of());
	}

	/**
	 * Returns a process scope's temporary directory.
	 *
	 * @param scope the process scope
	 * @return the directory
	 * @throws UncheckedIOException if the directory cannot be created
	 */
	private static Path temporaryDirectory(JvmScope scope)
	{
		try
		{
			return scope.temporaryDirectory();
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}
}
