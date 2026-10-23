package com.holdmyspot.cat.common.fakes.test;

import com.holdmyspot.cat.common.AbstractToolScope;
import com.holdmyspot.cat.common.ToolScope;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Supplies {@link ToolScope} services without a session, using the build's temporary directory.
 */
public final class TestToolScope extends AbstractToolScope
{
	/**
	 * Creates an open test tool scope.
	 */
	public TestToolScope()
	{
		super(new TestJvmScope(), Path.of(""), Map.of(), List.of());
	}
}
