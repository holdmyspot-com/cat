package com.holdmyspot.cat.common.build;

import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.secrets.BuildAdmissionAccess;
import com.holdmyspot.cat.common.secrets.BuildAdmissionSecrets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongConsumer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns the typed admission path for reviewed, isolated Maven gates.
 */
public final class BuildAdmission
{
	static
	{
		BuildAdmissionSecrets.setBuildAdmissionAccess(new BuildAdmissionAccess()
		{
			@Override
			public BuildAdmission create(JvmScope scope, HostContext context, BuildObservations observations, Clock clock,
				Map<String, String> inheritedEnvironment, LongConsumer forceStop)
			{
				return new BuildAdmission(scope, context, observations, clock, inheritedEnvironment, forceStop);
			}

			@Override
			public LongConsumer systemForceStop()
			{
				return BuildWorker::forceStop;
			}
		});
	}

	private final BuildObservations observations;
	private final JvmScope scope;
	private final HostContext context;
	private final Map<String, String> inheritedEnvironment;
	private final BuildReservations reservations;
	private final BuildWorker worker;

	/**
	 * Creates the shared controller and its process observation collaborators.
	  *
	 * @param scope process services
	  *
	 * @param context shared host context
	  *
	 * @param observations resource observations
	  *
	 * @param clock wall clock
	  *
	 * @param inheritedEnvironment caller environment
	 */
	public BuildAdmission(JvmScope scope, HostContext context, BuildObservations observations, Clock clock,
		Map<String, String> inheritedEnvironment)
	{
		this(scope, context, observations, clock, inheritedEnvironment, BuildWorker::forceStop);
	}

	/**
	 * Creates the shared controller with its process stop request.
	 *
	 * @param scope process services
	 * @param context shared host context
	 * @param observations resource observations
	 * @param clock wall clock
	 * @param inheritedEnvironment caller environment
	 * @param forceStop request to forcibly stop one process after identity validation
	 */
	private BuildAdmission(JvmScope scope, HostContext context, BuildObservations observations, Clock clock,
		Map<String, String> inheritedEnvironment, LongConsumer forceStop)
	{
		JvmScope checkedScope = Objects.requireNonNull(scope, "scope");
		this.context = Objects.requireNonNull(context, "context");
		this.scope = checkedScope;
		this.observations = Objects.requireNonNull(observations, "observations");
		Objects.requireNonNull(clock, "clock");
		this.inheritedEnvironment = Map.copyOf(Objects.requireNonNull(inheritedEnvironment, "inheritedEnvironment"));
		this.reservations = new BuildReservations(checkedScope.fileLocks(), context, clock);
		this.worker = new BuildWorker(checkedScope, observations, clock, this.inheritedEnvironment, forceStop);
	}

	/**
	 * Constructs the one supported focused Maven command.
	  *
	 * @param gate reviewed Maven gate
	  *
	 * @return complete immutable argv
	 */
	public static List<String> command(MavenGate gate)
	{
		Objects.requireNonNull(gate, "gate");
		List<String> arguments = new ArrayList<>();
		arguments.add("client/mvnw");
		arguments.add("-q");
		arguments.add("-f");
		arguments.add("client/pom.xml");
		arguments.add("-pl");
		arguments.add(String.join(",", gate.modules));
		arguments.add("-am");
		arguments.add("-Dtest=" + String.join(",", gate.tests));
		arguments.add("-Dsurefire.failIfNoSpecifiedTests=false");
		arguments.add("verify");
		return List.copyOf(arguments);
	}

	/**
	 * Applies the strict memory, CPU and worker-count admission boundaries.
	 *
	 * @param availableBytes current MemAvailable bytes
	 * @param heldBytes all outstanding reservation bytes
	 * @param candidateBytes candidate reservation bytes
	 * @param cpuLoad latest host CPU load
	 * @param activeWorkers current reservation count
	 * @param availableCpus usable processor count
	 * @return true only when every strict admission threshold is satisfied
	 */
	public static boolean resourcesPermit(long availableBytes, long heldBytes, long candidateBytes, double cpuLoad,
		int activeWorkers, int availableCpus)
	{
		if (availableBytes < 0 || heldBytes < 0 || candidateBytes < 0 || !Double.isFinite(cpuLoad) ||
			cpuLoad < 0 || cpuLoad >= 0.95 || activeWorkers < 0 || availableCpus <= 0 ||
			activeWorkers >= availableCpus)
			return false;
		try
		{
			long remaining = Math.subtractExact(Math.subtractExact(availableBytes, heldBytes), candidateBytes);
			return remaining > 8L * 1024 * 1024 * 1024;
		}
		catch (ArithmeticException _)
		{
			return false;
		}
	}

	/**
	 * Runs one required job through reservation, process observation and terminal recording.
	  *
	 * @param job required job
	  *
	 * @param evidence owned evidence root
	  *
	 * @return terminal job result
	  *
	 * @throws IOException if admission, process execution, or receipt persistence fails
	 */
	public JobResult run(Job job, Path evidence) throws IOException
	{
		Objects.requireNonNull(job, "job");
		Objects.requireNonNull(evidence, "evidence");
		validateJob(job, false);
		Path normalizedEvidence = resolveWithoutLinks(evidence.toAbsolutePath().normalize());
		validateEvidence(normalizedEvidence);
		BuildReservations.Claim claim = claimWhenAvailable(job, normalizedEvidence);
		return worker.run(job, claim, reservations, normalizedEvidence);
	}

	/**
	 * Runs all required jobs through independent ledger claims and concurrent owned workers.
	  *
	 * @param jobs finite required jobs
	  *
	 * @param evidence owned evidence root
	  *
	 * @return all terminal job results
	  *
	 * @throws IOException if admission, process execution, or receipt persistence fails
	 */
	public BatchResult run(List<Job> jobs, Path evidence) throws IOException
	{
		Objects.requireNonNull(jobs, "jobs");
		Objects.requireNonNull(evidence, "evidence");
		List<Job> submitted = new ArrayList<>(jobs);
		Set<String> identifiers = new HashSet<>();
		List<ValidatedJob> validated = new ArrayList<>();
		for (Job job : submitted)
		{
			Objects.requireNonNull(job, "job");
			if (!identifiers.add(job.jobId()))
				throw new IllegalArgumentException("Duplicate build job identifier: " + job.jobId());
			validateJob(job, true);
			validated.add(validatedJob(job));
		}
		validateBatchIsolation(validated);
		Path normalizedEvidence = resolveWithoutLinks(evidence.toAbsolutePath().normalize());
		validateEvidence(normalizedEvidence);
		Files.createDirectories(normalizedEvidence);
		return executeBatch(submitted, normalizedEvidence);
	}

	/**
	 * Ensures the owned run root cannot overwrite the shared context or reservation ledger.
	 *
	 * @param evidence resolved owned evidence root
	 * @throws IOException if the evidence root overlaps shared state
	 */
	private void validateEvidence(Path evidence) throws IOException
	{
		if (overlaps(evidence, reservations.ledgerPath()) || overlaps(evidence, context.contextFile))
			throw new IOException("Build evidence overlaps the shared context or ledger: " + evidence);
	}

	/**
	 * Obtains normalized path identities after the individual request has passed its checks.
	 *
	 * @param job validated request
	 * @return normalized project, invocation and output paths
	 * @throws IOException if any path cannot be safely resolved
	 */
	private ValidatedJob validatedJob(Job job) throws IOException
	{
		Path project = resolveWithoutLinks(job.project());
		Path invocation = resolveWithoutLinks(job.invocation());
		List<Path> outputs = new ArrayList<>();
		for (String output : job.outputRoots())
			outputs.add(resolveWithoutLinks(project.resolve(output)));
		return new ValidatedJob(project, invocation, List.copyOf(outputs));
	}

	/**
	 * Rejects shared writable or cross-project paths before the first ledger claim.
	 *
	 * @param jobs all normalized job paths
	 * @throws IOException if any pair can write through another job's owned roots
	 */
	private static void validateBatchIsolation(List<ValidatedJob> jobs) throws IOException
	{
		for (int firstIndex = 0; firstIndex < jobs.size(); firstIndex = firstIndex + 1)
		{
			ValidatedJob first = jobs.get(firstIndex);
			for (int secondIndex = firstIndex + 1; secondIndex < jobs.size(); secondIndex = secondIndex + 1)
			{
				ValidatedJob second = jobs.get(secondIndex);
				if (!first.project().equals(second.project()) && overlaps(first.project(), second.project()))
					throw new IOException("Build projects overlap: " + first.project() + " and " + second.project());
				if (overlaps(first.invocation(), second.invocation()))
					throw new IOException("Build invocations overlap: " + first.invocation() + " and " +
						second.invocation());
				for (Path output : first.outputs())
				{
					if (overlaps(output, second.invocation()))
						throw new IOException("Build output overlaps another invocation: " + output);
					for (Path otherOutput : second.outputs())
						if (overlaps(output, otherOutput))
							throw new IOException("Build outputs overlap: " + output + " and " + otherOutput);
				}
				for (Path output : second.outputs())
					if (overlaps(output, first.invocation()))
						throw new IOException("Build output overlaps another invocation: " + output);
				if (!first.project().equals(second.project()) &&
					(overlaps(first.invocation(), second.project()) || overlaps(second.invocation(), first.project())))
					throw new IOException("Build invocation overlaps another project root");
			}
		}
	}

	/**
	 * Schedules every validated job and retains all owned worker outcomes.
	 *
	 * @param jobs immutable input order
	 * @param evidence validated run evidence root
	 * @return input-ordered terminal results
	 * @throws IOException if admission or any owned worker has an infrastructure failure
	 */
	private BatchResult executeBatch(List<Job> jobs, Path evidence) throws IOException
	{
		BlockingQueue<WorkerOutcome> completions = new LinkedBlockingQueue<>();
		List<Thread> ownedWorkers = new ArrayList<>();
		boolean[] started = new boolean[jobs.size()];
		JobResult[] results = new JobResult[jobs.size()];
		IOException[] workerFailures = new IOException[jobs.size()];
		int finished = 0;
		IOException primaryFailure = null;
		boolean callerInterrupted = false;
		InterruptedException interruption = null;
		while (finished < jobs.size() && primaryFailure == null && !callerInterrupted)
		{
			try
			{
				if (Thread.interrupted())
				{
					callerInterrupted = true;
					interruption = new InterruptedException("Caller interrupted before batch admission");
					break;
				}
				WorkerOutcome completed;
				while (true)
				{
					completed = completions.poll();
					if (completed == null)
						break;
					finished = recordOutcome(completed, results, workerFailures, finished);
					if (completed.failure() != null)
					{
						primaryFailure = completed.failure();
						break;
					}
				}
				if (primaryFailure != null || finished == jobs.size())
					break;
				for (int index = 0; index < jobs.size(); index = index + 1)
				{
					if (Thread.interrupted())
					{
						callerInterrupted = true;
						interruption = new InterruptedException("Caller interrupted during batch admission");
						break;
					}
					while (true)
					{
						completed = completions.poll();
						if (completed == null)
							break;
						finished = recordOutcome(completed, results, workerFailures, finished);
						if (completed.failure() != null)
						{
							primaryFailure = completed.failure();
							break;
						}
					}
					if (primaryFailure != null || finished == jobs.size())
						break;
					if (started[index])
						continue;
					BuildReservations.Claim claim = reservations.tryClaim(jobs.get(index), observations, evidence);
					if (claim.kind() == BuildReservations.Claim.Kind.DEFERRED)
					{
						continue;
					}
					started[index] = true;
					int jobIndex = index;
					Thread thread = Thread.ofVirtual().name("build-admission-" + index).start(() ->
					{
						try
						{
							completions.add(new WorkerOutcome(jobIndex,
								worker.run(jobs.get(jobIndex), claim, reservations, evidence), null));
						}
						catch (IOException failure)
						{
							completions.add(new WorkerOutcome(jobIndex, null, failure));
						}
					catch (RuntimeException failure)
						{
							IOException contextual = new IOException("Build worker failed unexpectedly for job " +
								jobs.get(jobIndex).jobId(), failure);
							completions.add(new WorkerOutcome(jobIndex, null, contextual));
						}
					});
					ownedWorkers.add(thread);
				}
				if (primaryFailure == null && !callerInterrupted && finished < jobs.size())
				{
					completed = completions.poll(100, TimeUnit.MILLISECONDS);
					if (completed != null)
					{
						finished = recordOutcome(completed, results, workerFailures, finished);
						if (completed.failure() != null)
							primaryFailure = completed.failure();
					}
				}
			}
			catch (InterruptedException exception)
			{
				callerInterrupted = true;
				interruption = exception;
			}
			catch (IOException exception)
			{
				primaryFailure = exception;
			}
			catch (RuntimeException exception)
			{
				primaryFailure = new IOException("Build admission failed", exception);
			}
		}
		if (primaryFailure != null || callerInterrupted)
			ownedWorkers.forEach(Thread::interrupt);
		InterruptedException cleanupInterruption = joinWorkers(ownedWorkers);
		if (cleanupInterruption != null)
		{
			callerInterrupted = true;
			if (interruption == null)
				interruption = cleanupInterruption;
		}
		WorkerOutcome completed;
		while (true)
		{
			completed = completions.poll();
			if (completed == null)
				break;
			finished = recordOutcome(completed, results, workerFailures, finished);
		}
		if (callerInterrupted)
		{
			IOException failure = new IOException("Interrupted while waiting for build workers", interruption);
			attachWorkerFailures(failure, workerFailures, null);
			Thread.currentThread().interrupt();
			throw failure;
		}
		if (primaryFailure != null)
		{
			attachWorkerFailures(primaryFailure, workerFailures, primaryFailure);
			throw primaryFailure;
		}
		return new BatchResult(List.of(results));
	}

	/**
	 * Records one completion in the fixed result slot for its input position.
	 *
	 * @param outcome worker result or failure
	 * @param results result slots
	 * @param failures failure slots
	 * @param finished current completion count
	 * @return incremented completion count
	 */
	private static int recordOutcome(WorkerOutcome outcome, JobResult[] results, IOException[] failures, int finished)
	{
		if (outcome.result() != null)
			results[outcome.index()] = outcome.result();
		else
			failures[outcome.index()] = outcome.failure();
		return finished + 1;
	}

	/**
	 * Joins every owned worker through repeated caller interruptions.
	 *
	 * @param workers all started worker threads
	 * @return first interruption observed while joining, if any
	 */
	private static InterruptedException joinWorkers(List<Thread> workers)
	{
		InterruptedException first = null;
		for (Thread worker : workers)
		{
			boolean joined = false;
			while (!joined)
			{
				try
				{
					worker.join();
					joined = true;
				}
				catch (InterruptedException exception)
				{
					if (first == null)
						first = exception;
					workers.forEach(Thread::interrupt);
				}
			}
		}
		return first;
	}

	/**
	 * Attaches worker failures in original job order, excluding the primary occurrence.
	 *
	 * @param primary original failure
	 * @param failures failures by job index
	 * @param alreadyAttached primary worker failure, if any
	 */
	private static void attachWorkerFailures(IOException primary, IOException[] failures, IOException alreadyAttached)
	{
		for (IOException failure : failures)
			if (failure != null && failure != alreadyAttached)
				primary.addSuppressed(failure);
	}

	/**
	 * Waits for one direct caller's deferred claim using the scope's bounded wait service.
	 *
	 * @param job requested Maven job
	 * @param evidence owned evidence directory
	 * @return admitted or calibration claim
	 * @throws IOException if admission observations fail or waiting is interrupted
	 */
	private BuildReservations.Claim claimWhenAvailable(Job job, Path evidence) throws IOException
	{
		while (true)
		{
			BuildReservations.Claim claim = reservations.tryClaim(job, observations, evidence);
			if (claim.kind() != BuildReservations.Claim.Kind.DEFERRED)
				return claim;
			try
			{
				scope.waiting().every(Duration.ofMillis(100)).pause();
			}
			catch (IOException exception)
			{
				throw new Failure(Failure.Kind.PROCESS_IO_FAILED, evidence, job.jobId(), null,
					"Could not wait for build admission", exception);
			}
		}
	}

	/**
	 * Reconciles existing reservations against current process observations.
	  *
	 * @param evidence owned evidence root
	  *
	 * @return recovery result
	  *
	 * @throws IOException if the ledger cannot be reconciled safely
	 */
	public RecoveryResult reconcile(Path evidence) throws IOException
	{
		return reservations.reconcile(observations, evidence);
	}

	/**
	 * Immutable host identity and shared ledger authority supplied by the parent workflow.
	 */
	public static final class HostContext
	{
		private final UUID contextId;
		private final UUID hostBootId;
		private final Path ledgerPath;
		private final Path contextFile;
		private final String authorityId;
		private final String authorityActor;
		private final List<Path> authorityEvidencePaths;
		private final String cleanupOwner;

		/**
		 * Creates a context that has passed serialized schema validation.
		 *
		 * @param contextId context identifier
		 * @param hostBootId host boot identifier
		 * @param ledgerPath shared ledger directory
		 * @param contextFile serialized context file
		 * @param authorityId authority identifier
		 * @param authorityActor authorizing actor
		 * @param authorityEvidencePaths authority evidence
		 * @param cleanupOwner cleanup owner
		 */
		private HostContext(UUID contextId, UUID hostBootId, Path ledgerPath, Path contextFile, String authorityId,
			String authorityActor, List<Path> authorityEvidencePaths, String cleanupOwner)
		{
			this.contextId = contextId;
			this.hostBootId = hostBootId;
			this.ledgerPath = ledgerPath.toAbsolutePath().normalize();
			this.contextFile = contextFile.toAbsolutePath().normalize();
			this.authorityId = authorityId;
			this.authorityActor = authorityActor;
			this.authorityEvidencePaths = List.copyOf(authorityEvidencePaths);
			this.cleanupOwner = cleanupOwner;
		}

		/**
		 * Reads the strict version-one context file.
		  *
		 * @param mapper JSON mapper
		  *
		 * @param file context file
		  *
		 * @return validated host context
		  *
		 * @throws IOException if the context is malformed or incomplete
		 */
		public static HostContext read(JsonMapper mapper, Path file) throws IOException
		{
			Objects.requireNonNull(mapper, "mapper");
			Objects.requireNonNull(file, "file");
			if (Files.size(file) > 16L * 1024 * 1024)
				throw new IOException("Context exceeds 16 MiB: " + file);
			JsonNode root;
			try
			{
				ObjectReader reader = mapper.readerFor(JsonNode.class).with(StreamReadFeature.STRICT_DUPLICATE_DETECTION).
					with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
				root = reader.readValue(Files.readAllBytes(file));
			}
			catch (JacksonException exception)
			{
				throw new IOException("Malformed build context: " + file + ": " + exception.getOriginalMessage(),
					exception);
			}
			if (root == null || !root.isObject() || root.size() != 8 || !root.path("format_version").canConvertToInt() ||
				root.path("format_version").intValue() != 1)
				throw new IOException("Invalid build context schema: " + file);
			try
			{
				UUID context = UUID.fromString(requiredText(root, "context_id"));
				UUID boot = UUID.fromString(requiredText(root, "host_boot_id"));
				Path base = file.toAbsolutePath().normalize().getParent();
				Path ledgerMember = Path.of(requiredText(root, "ledger_path"));
				if (ledgerMember.isAbsolute() || ledgerMember.toString().contains(".."))
					throw new IOException("Context ledger_path must be relative and canonical: " + file);
				Path ledger = base.resolve(ledgerMember).normalize();
				List<Path> authority = new ArrayList<>();
				JsonNode paths = root.path("authority_evidence_paths");
				if (!paths.isArray() || paths.isEmpty())
					throw new IOException("Context requires authority evidence paths: " + file);
				for (JsonNode path : paths)
				{
					if (!path.isString())
						throw new IOException("Invalid authority evidence path: " + file);
					Path member = Path.of(path.stringValue());
					if (member.isAbsolute() || member.toString().contains(".."))
						throw new IOException("Authority evidence path must be relative and canonical: " + file);
					authority.add(base.resolve(member).normalize());
				}
				return new HostContext(context, boot, ledger, file, requiredText(root, "authority_id"),
					requiredText(root, "authority_actor"), authority, requiredText(root, "cleanup_owner"));
			}
			catch (IllegalArgumentException exception)
			{
				throw new IOException("Invalid UUID in build context: " + file, exception);
			}
		}

		/**
		 * Reads one required nonblank string field.
		  *
		 * @param node JSON object
		  *
		 * @param name member name
		  *
		 * @return member value
		  *
		 * @throws IOException if the field is absent or not a string
		 */
		private static String requiredText(JsonNode node, String name) throws IOException
		{
			JsonNode value = node.path(name);
			if (!value.isString() || value.stringValue().isBlank())
				throw new IOException("Missing or invalid " + name);
			return value.stringValue();
		}

		/**
		 * Returns context UUID.
		  *
		 * @return context identifier
		 */
		public UUID contextId()
		{
			return contextId;
		}
		/**
		 * Returns expected host boot UUID.
		  *
		 * @return host boot identifier
		 */
		public UUID hostBootId()
		{
			return hostBootId;
		}
		/**
		 * Returns shared ledger directory.
		  *
		 * @return ledger path
		 */
		public Path ledgerPath()
		{
			return ledgerPath;
		}
		/**
		 * Returns the directory that anchors serialized context paths.
		 *
		 * @return context file parent
		 */
		public Path contextDirectory()
		{
			return contextFile.getParent();
		}
		/**
		 * Returns host authority identity.
		  *
		 * @return authority identifier
		 */
		public String authorityId()
		{
			return authorityId;
		}
		/**
		 * Returns authorizing actor.
		  *
		 * @return authority actor
		 */
		public String authorityActor()
		{
			return authorityActor;
		}
		/**
		 * Returns authority evidence paths.
		  *
		 * @return immutable evidence paths
		 */
		public List<Path> authorityEvidencePaths()
		{
			return authorityEvidencePaths;
		}
		/**
		 * Returns cleanup owner.
		  *
		 * @return owner identifier
		 */
		public String cleanupOwner()
		{
			return cleanupOwner;
		}
	}

	/**
	 * Immutable resource-affecting reviewed Maven profile.
	 */
	public static final class MavenGate
	{
		private final List<String> modules;
		private final List<String> tests;
		private final String familyId;
		private final String definitionManifestId;
		private final String commandProfileId;

		/**
		 * Creates an immutable profile after its derived values have been computed.
		 *
		 * @param modules module selectors
		 * @param tests test selectors
		 * @param familyId derived family identifier
		 * @param definitionManifestId reviewed file identity
		 * @param commandProfileId execution profile identity
		 */
		private MavenGate(List<String> modules, List<String> tests, String familyId, String definitionManifestId,
			String commandProfileId)
		{
			this.modules = List.copyOf(modules);
			this.tests = List.copyOf(tests);
			this.familyId = familyId;
			this.definitionManifestId = definitionManifestId;
			this.commandProfileId = commandProfileId;
		}

		/**
		 * Creates a gate and derives its family identity from reviewed files and execution profile.
		  *
		 * @param project project root
		  *
		 * @param modules Maven module selectors
		  *
		 * @param tests selected TestNG classes
		  *
		 * @param resourceDefinitionPaths reviewed resource definitions
		  *
		 * @param resourceReviewId review identifier
		  *
		 * @param resourceReviewer reviewer identity
		  *
		 * @return immutable gate
		  *
		 * @throws IOException if a resource definition cannot be read
		 */
		public static MavenGate of(Path project, List<String> modules, List<String> tests,
			List<Path> resourceDefinitionPaths, String resourceReviewId, String resourceReviewer) throws IOException
		{
			Objects.requireNonNull(project, "project");
			List<String> checkedModules = checkedSelectors(modules, "modules");
			List<String> checkedTests = checkedSelectors(tests, "tests");
			if (resourceReviewId == null || resourceReviewId.isBlank() || resourceReviewer == null ||
				resourceReviewer.isBlank())
				throw new IllegalArgumentException("Resource review identity is required");
			Path root = project.toRealPath();
			StringBuilder definitions = new StringBuilder(resourceReviewId).append('\n').append(resourceReviewer);
			for (Path definition : List.copyOf(resourceDefinitionPaths))
			{
				Path resolved = root.resolve(definition).normalize();
				if (!resolved.startsWith(root))
					throw new IllegalArgumentException("Definition escapes project: " + definition);
				definitions.append('\n').append(definition).append(':');
				if (Files.exists(resolved))
					definitions.append(HexFormat.of().formatHex(digest(Files.readAllBytes(resolved))));
				else
					definitions.append("absent");
			}
			String manifest = hash(definitions.toString());
			String profile = hash(String.join("\n", command(new MavenGate(checkedModules, checkedTests, "", "", ""))) +
				"\njava=" + System.getProperty("java.version") + "\nlauncher=project_maven_v3");
			return new MavenGate(checkedModules, checkedTests, hash(manifest + profile), manifest, profile);
		}

		/**
		 * Validates and copies the selector list.
		  *
		 * @param values requested selectors
		  *
		 * @param name selector category
		  *
		 * @return immutable selectors
		 */
		private static List<String> checkedSelectors(List<String> values, String name)
		{
			List<String> copy = List.copyOf(Objects.requireNonNull(values, name));
			if (copy.isEmpty() || copy.stream().anyMatch(value -> value == null || value.isBlank() || value.contains(" ")))
				throw new IllegalArgumentException("Invalid " + name + " selectors");
			return copy;
		}
		/**
		 * Returns module selectors.
		  *
		 * @return immutable module selectors
		 */
		public List<String> modules()
		{
			return modules;
		}
		/**
		 * Returns TestNG selectors.
		  *
		 * @return immutable test selectors
		 */
		public List<String> tests()
		{
			return tests;
		}
		/**
		 * Returns the derived resource-family identifier.
		  *
		 * @return family identity
		 */
		public String familyId()
		{
			return familyId;
		}
		/**
		 * Returns reviewed definition identity.
		  *
		 * @return definition manifest identity
		 */
		public String definitionManifestId()
		{
			return definitionManifestId;
		}
		/**
		 * Returns the derived execution profile identity.
		  *
		 * @return command profile identity
		 */
		public String commandProfileId()
		{
			return commandProfileId;
		}
	}

	/**
	 * One immutable focused build request.
	  *
	 * @param jobId job identifier
	  *
	 * @param assignmentId work assignment identifier
	  *
	 * @param project repository root
	  *
	 * @param gate reviewed Maven gate
	  *
	 * @param invocation owned invocation root
	  *
	 * @param sourceManifestId immutable source identity
	 * @param excludedRoots reviewed source exclusions
	 * @param outputRoots reviewed Maven output roots
	 */
	public record Job(String jobId, String assignmentId, Path project, MavenGate gate, Path invocation,
		String sourceManifestId, List<String> excludedRoots, List<String> outputRoots)
	{
		/**
		 * Validates request identity and distinct canonical roots.
		 *
		 * @param jobId job identifier
		 * @param assignmentId work assignment identifier
		 * @param project repository root
		 * @param gate reviewed Maven gate
		 * @param invocation owned invocation root
		 * @param sourceManifestId source identity
		 * @param excludedRoots reviewed source exclusions
		 * @param outputRoots reviewed Maven output roots
		 */
		public Job
		{
			if (jobId == null || jobId.isBlank() || assignmentId == null || assignmentId.isBlank() ||
				sourceManifestId == null || sourceManifestId.isBlank())
				throw new IllegalArgumentException("Job and assignment identities must be nonblank");
			Objects.requireNonNull(gate, "gate");
			project = Objects.requireNonNull(project, "project").toAbsolutePath().normalize();
			invocation = Objects.requireNonNull(invocation, "invocation").toAbsolutePath().normalize();
			excludedRoots = canonicalPaths(excludedRoots, "excluded roots", true);
			outputRoots = canonicalPaths(outputRoots, "output roots", false);
		}
	}

	/**
	 * Verifies invocation separation against the reviewed source and output boundaries before ledger mutation.
	 *
		 * @param job requested build
	 * @param requireFreshInvocation whether the batch must own a new invocation root
		 * @throws IOException if paths cannot be resolved safely or overlap protected data
	 */
	private void validateJob(Job job, boolean requireFreshInvocation) throws IOException
	{
		Path project = resolveWithoutLinks(job.project());
		Path invocation = resolveWithoutLinks(job.invocation());
		Path ledger = resolveWithoutLinks(reservations.ledgerPath());
		Path contextFile = resolveWithoutLinks(context.contextFile);
		if (requireFreshInvocation && Files.exists(invocation, java.nio.file.LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Build invocation root is not fresh: " + invocation);
		if (invocation.equals(project) || overlaps(invocation, ledger) ||
			overlaps(invocation, contextFile))
			throw new IOException("Invocation overlaps project, build context, or shared ledger: " + invocation);
		if (project.startsWith(invocation))
			throw new IOException("Invocation contains project root: " + invocation);
		for (String output : job.outputRoots())
		{
			Path outputPath = resolveWithoutLinks(project.resolve(output));
			if (overlaps(invocation, outputPath))
				throw new IOException("Invocation overlaps reviewed output: " + output);
		}
		if (!invocation.startsWith(project))
			return;
		String relative = project.relativize(invocation).toString().replace(java.io.File.separatorChar, '/');
		if (containsGitSegment(relative))
			throw new IOException("Invocation enters Git administration data: " + relative);
		String exclusion = job.excludedRoots().stream().filter(root -> isWithin(relative, root)).
			max(java.util.Comparator.comparingInt(String::length)).orElse(null);
		if (exclusion == null)
			throw new IOException("Invocation is not covered by a reviewed exclusion: " + relative);
	}

	/**
	 * Tests whether either normalized path contains the other.
	 *
	 * @param first first path
	 * @param second second path
	 * @return true if the paths overlap
	 */
	private static boolean overlaps(Path first, Path second)
	{
		return first.startsWith(second) || second.startsWith(first);
	}

	/**
	 * Immutable path identities used only during complete-batch preflight.
	 *
	 * @param project normalized project root
	 * @param invocation normalized fresh invocation root
	 * @param outputs normalized writable Maven outputs
	 */
	private record ValidatedJob(Path project, Path invocation, List<Path> outputs)
	{
	}

	/**
	 * One worker's terminal event as observed by the scheduler.
	 *
	 * @param index job's original input position
	 * @param result terminal build result
	 * @param failure infrastructure failure
	 */
	private record WorkerOutcome(int index, JobResult result, IOException failure)
	{
	}

	/**
	 * Resolves a possibly not-yet-created path while rejecting symbolic-link ancestors.
	 *
	 * @param path normalized absolute path
	 * @return real path with the missing suffix preserved
	 * @throws IOException if no ancestor resolves or a symbolic link is present
	 */
	private static Path resolveWithoutLinks(Path path) throws IOException
	{
		Path current = path.getRoot();
		for (Path segment : path)
		{
			current = current.resolve(segment);
			if (Files.exists(current, java.nio.file.LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current))
				throw new IOException("Invocation route contains a symbolic link: " + current);
		}
		Path existing = path;
		while (existing != null && !Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS))
			existing = existing.getParent();
		if (existing == null)
			throw new IOException("Invocation path has no resolvable ancestor: " + path);
		return existing.toRealPath().resolve(existing.relativize(path)).normalize();
	}

	/**
	 * Validates exact project-relative path spellings.
	 *
	 * @param values caller paths
	 * @param label field name for diagnostics
	 * @param allowGitRoot whether the exact .git exclusion spelling is permitted
	 * @return immutable paths
	 */
	private static List<String> canonicalPaths(List<String> values, String label, boolean allowGitRoot)
	{
		List<String> result = List.copyOf(Objects.requireNonNull(values, label));
		Set<String> seen = new HashSet<>();
		for (String value : result)
		{
			if (value.startsWith("/") || value.contains("\\") || value.indexOf('\0') >= 0)
				throw new IllegalArgumentException("Invalid " + label + " path: " + value);
			for (String segment : value.split("/", -1))
			{
				boolean permittedGitRoot = allowGitRoot && value.equals(".git");
				if (segment.isEmpty() || segment.equals(".") || segment.equals("..") ||
					segment.equalsIgnoreCase(".git") && !permittedGitRoot)
					throw new IllegalArgumentException("Invalid " + label + " path: " + value);
			}
			if (!seen.add(value.toLowerCase(java.util.Locale.ROOT)))
				throw new IllegalArgumentException("Path alias in " + label + ": " + value);
		}
		return result;
	}

	/**
	 * Tests whether a project-relative path is equal to or below a root.
	 *
	 * @param path project-relative path
	 * @param root project-relative root
	 * @return whether the path is contained by the root
	 */
	private static boolean isWithin(String path, String root)
	{
		return path.equals(root) || path.startsWith(root + "/");
	}

	/**
	 * Tests whether a path contains Git administration data.
	 *
	 * @param path project-relative path
	 * @return whether a segment names Git administration data
	 */
	private static boolean containsGitSegment(String path)
	{
		return java.util.Arrays.stream(path.split("/")).anyMatch(segment -> segment.equalsIgnoreCase(".git"));
	}

	/**
	 * Terminal state of one required build.
	  *
	 * @param jobId job identifier
	  *
	 * @param status terminal status
	  *
	 * @param exitCode process exit code when available
	  *
	 * @param diagnostics retained diagnostics path
	  *
	 * @param admissionReceiptId admission receipt identifier
	  *
	 * @param terminalReceiptId terminal receipt identifier
	 * @param terminalReceiptPath relative terminal receipt path
	 */
	public record JobResult(String jobId, String status, Integer exitCode, Path diagnostics, String admissionReceiptId,
		String terminalReceiptId, Path terminalReceiptPath)
{
		/**
		 * Returns whether the Maven job passed.
		  *
		 * @return true when the status is passed
		 */
		public boolean passed()
		{
			return "passed".equals(status);
		}
	}
	/**
	 * Results for every job in one submitted batch.
	  *
	 * @param results required job results
	 */
	public record BatchResult(List<JobResult> results)
{
		/**
		 * Copies terminal results.
		  *
		 * @param results terminal results
		 */
		public BatchResult
		{
			results = List.copyOf(results);
		}
		/**
		 * Returns whether all required jobs passed.
		  *
		 * @return true when every job passed
		 */
		public boolean passed()
		{
			return results.stream().allMatch(JobResult::passed);
		}
	}
	/**
	 * Result of reservation recovery.
	  *
	 * @param reconciled whether no uncertain reservation remains
	  *
	 * @param unresolvedReservations unresolved reservation identities
	 */
	public record RecoveryResult(boolean reconciled, List<String> unresolvedReservations)
{
		/**
		 * Copies unresolved reservation identifiers.
		  *
		 * @param reconciled whether reconciliation is complete
		  *
		 * @param unresolvedReservations remaining reservation identities
		 */
		public RecoveryResult
		{
			unresolvedReservations = List.copyOf(unresolvedReservations);
		}
	}

	/**
	 * Contextual fail-closed controller error.
	 */
	public static final class Failure extends IOException
	{
		private static final long serialVersionUID = 1L;
		private final Kind kind;
		private final transient Path path;
		private final String jobId;
		private final String reservationId;
		/**
		 * Creates a typed controller failure with the originating cause.
		 *
		 * @param kind failure category
		 * @param path relevant path
		 * @param jobId affected job
		 * @param reservationId affected reservation
		 * @param message contextual message
		 * @param cause original failure
		 */
		public Failure(Kind kind, Path path, String jobId, String reservationId, String message, Throwable cause)
		{
			super(message, cause); this.kind = Objects.requireNonNull(kind); this.path = path; this.jobId = jobId;
			this.reservationId = reservationId;
		}
		/**
		 * Creates a typed controller failure without an underlying cause.
		 *
		 * @param kind failure category
		 * @param path relevant path
		 * @param jobId affected job
		 * @param reservationId affected reservation
		 * @param message contextual message
		 */
		public Failure(Kind kind, Path path, String jobId, String reservationId, String message)
		{
			this(kind, path, jobId, reservationId, message, null);
		}
		/**
		 * Returns failure category.
		 *
		 * @return failure kind
		 */
		public Kind kind()
		{
			return kind;
		}
		/**
		 * Returns relevant path.
		 *
		 * @return path, if available
		 */
		public Path path()
		{
			return path;
		}
		/**
		 * Returns affected job.
		 *
		 * @return job ID, if available
		 */
		public String jobId()
		{
			return jobId;
		}
		/**
		 * Returns affected reservation.
		 *
		 * @return reservation ID, if available
		 */
		public String reservationId()
		{
			return reservationId;
		}
		/**
		 * Stable failure categories.
		 */
		public enum Kind
		{
			INVALID_REQUEST, INVALID_OBSERVATION, HOST_MISMATCH, INVALID_CALIBRATION, CORRUPT_LEDGER,
			RECOVERY_REQUIRED, PROCESS_START_FAILED, PROCESS_IO_FAILED
		}
	}

	/**
	 * Computes a SHA-256 digest.
	 *
	 * @param bytes input bytes
	 * @return digest bytes
	 */
	private static byte[] digest(byte[] bytes)
	{
		try
		{
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		}
		catch (NoSuchAlgorithmException impossible)
		{
			throw new IllegalStateException(impossible);
		}
	}
	/**
	 * Computes a hexadecimal SHA-256 digest for text.
	 *
	 * @param value input text
	 * @return lowercase hexadecimal digest
	 */
	private static String hash(String value)
	{
		return HexFormat.of().formatHex(digest(value.getBytes(StandardCharsets.UTF_8)));
	}
}
