// Stands in for a Java runtime's bin\java.exe in the Bats tests of CAT's Windows launchers, which start java.exe as a
// Windows program and clear its environment, so the fake learns what to do only from files beside its runtime.
//
// The runtime is the folder above the fake's bin folder; the fake's folder of files is the folder above the runtime,
// which is the installer data directory when the runtime is an installation's runtime and the plugin's bin folder when
// it is a plugin's runtime.
//
// Run with the single argument -version, it exits with the number in <runtime>\version-status, or 0 without that
// file, as a runtime's java -version does. Otherwise it writes what it received to <folder>\java-invocation.txt: one
// line [argument] for each argument, a line cwd=<working directory>, and a line env:<NAME>=<value> for each environment
// variable, with its name in upper case and sorted by it. It then copies <folder>\java-output to its standard output
// when that file exists, and exits with the number in <folder>\java-status, or 0 without that file.
using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Text;

public static class FakeJava
{
	public static int Main(string[] arguments)
	{
		string runtime = Path.GetDirectoryName(Path.GetDirectoryName(Assembly.GetEntryAssembly().Location));
		if (arguments.Length == 1 && arguments[0] == "-version")
			return Status(Path.Combine(runtime, "version-status"));
		string folder = Path.GetDirectoryName(runtime);

		StringBuilder report = new StringBuilder();
		foreach (string argument in arguments)
			report.Append('[').Append(argument).Append("]\n");
		report.Append("cwd=").Append(Environment.CurrentDirectory).Append('\n');
		SortedDictionary<string, string> variables = new SortedDictionary<string, string>(StringComparer.Ordinal);
		foreach (DictionaryEntry variable in Environment.GetEnvironmentVariables())
			variables[((string) variable.Key).ToUpperInvariant()] = (string) variable.Value;
		foreach (KeyValuePair<string, string> variable in variables)
			report.Append("env:").Append(variable.Key).Append('=').Append(variable.Value).Append('\n');
		File.WriteAllText(Path.Combine(folder, "java-invocation.txt"), report.ToString(), new UTF8Encoding(false));

		string output = Path.Combine(folder, "java-output");
		if (File.Exists(output))
		{
			byte[] bytes = File.ReadAllBytes(output);
			using (Stream stream = Console.OpenStandardOutput())
				stream.Write(bytes, 0, bytes.Length);
		}
		return Status(Path.Combine(folder, "java-status"));
	}

	// Returns the number that a file holds, or 0 when the file does not exist.
	private static int Status(string file)
	{
		if (!File.Exists(file))
			return 0;
		return int.Parse(File.ReadAllText(file).Trim());
	}
}
