package io.github.cowwoc.cat.distribution.bundle.secrets;

import io.github.cowwoc.cat.distribution.bundle.ModuleDescriptorPatcher;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.spi.ToolProvider;

/**
 * Patches a JAR through {@link ModuleDescriptorPatcher} with caller-supplied JDK tools.
 */
@FunctionalInterface
public interface ModuleDescriptorPatcherAccess
{
	/**
	 * Patches a JAR using supplied in-process JDK tools.
	 *
	 * @param javac the Java compiler tool
	 * @param jar the JAR tool
	 * @param source the original JAR
	 * @param destination the patched JAR
	 * @param moduleName the module name
	 * @param exportedPackages the packages to export
	 * @return the patched JAR
	 * @throws IOException if patching fails
	 */
	Path patch(ToolProvider javac, ToolProvider jar, Path source, Path destination, String moduleName,
		Set<String> exportedPackages) throws IOException;
}
