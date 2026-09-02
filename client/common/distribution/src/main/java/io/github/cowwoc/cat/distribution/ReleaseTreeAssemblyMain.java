package io.github.cowwoc.cat.distribution;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Command-line boundary for combining native release inputs after the platform build matrix completes.
 */
public final class ReleaseTreeAssemblyMain
{
	/**
	 * Assembles the Personal and Commercial release trees.
	 *
	 * @param args the release version, native-input root, and nonexistent output root
	 * @throws IOException if assembly cannot complete
	 */
	public static void main(String[] args) throws IOException
	{
		if (args == null)
			throw new NullPointerException("args must not be null");
		if (args.length != 3)
			throw new IllegalArgumentException("Usage: ReleaseTreeAssemblyMain VERSION INPUTS_ROOT OUTPUT_ROOT");
		ReleaseTreeAssembler.assemble(args[0], Path.of(args[1]), Path.of(args[2]));
	}

	/**
	 * Prevents construction of this command-line owner.
	 */
	private ReleaseTreeAssemblyMain()
	{
	}
}
