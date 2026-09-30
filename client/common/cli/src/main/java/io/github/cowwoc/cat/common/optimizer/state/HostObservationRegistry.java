package io.github.cowwoc.cat.common.optimizer.state;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * Workflow-root transaction for the one-use host observation and exact invocation association.
 */
final class HostObservationRegistry
{
	private final Path workflowRoot;
	private final Path lockFile;
	private final Path intentFile;
	private final Path indexFile;
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Uses the locator's trusted workflow root for the global one-use index.
	 *
	 * @param workflowRoot trusted canonical workflow root
	 */
	HostObservationRegistry(Path workflowRoot)
	{
		this.workflowRoot = Objects.requireNonNull(workflowRoot, "workflowRoot");
		lockFile = this.workflowRoot.resolve("host-observation-index.lock");
		intentFile = this.workflowRoot.resolve("host-observation-intent.json");
		indexFile = this.workflowRoot.resolve("host-observation-index.json");
	}

	/**
	 * Publishes canonical observation bytes and its one-use invocation association as one recoverable operation.
	 *
	 * @param store pinned campaign store
	 * @param binding exact campaign binding
	 * @param invocationId exact command invocation
	 * @param registrationRef original registration evidence
	 * @param canonicalBytes canonical C9 observation bytes
	 * @return immutable observation reference
	 * @throws IOException if bytes, registration, uniqueness, or persistence fails
	 */
	EvidenceRef publish(CampaignStore store, CampaignBinding binding, String invocationId,
		EvidenceRef registrationRef,
		byte[] canonicalBytes) throws IOException
	{
		if (store == null || binding == null || invocationId == null || registrationRef == null ||
			canonicalBytes == null)
			throw new NullPointerException("Host publication identity and bytes are required");
		return locked(() ->
		{
			Observation observation = parseObservation(canonicalBytes);
			EvidenceRef reference = new EvidenceRef("host-interrupt-observations", sha256(canonicalBytes));
			Association requested = new Association(1, binding, invocationId, registrationRef, reference,
				observation.tokenDigest(), observation.hostSessionId(), observation.hostTurnId());
			InvocationStopRegistration registration = registration(store, binding, invocationId,
				registrationRef);
			List<Association> associations = readIndex();
			for (Association existing : associations)
			{
				if (existing.equals(requested))
				{
					if (!registration.hostObservationRef().equals(java.util.Optional.of(reference)))
						throw new IOException("Published observation is not attached to its registration");
					if (!Arrays.equals(store.readImmutableJson(reference), canonicalBytes))
						throw new IOException("Published observation bytes differ from their reference");
					return reference;
				}
				if (conflicts(existing, requested))
					throw new IOException("Host observation identity is already used");
			}
			if (registration.hostObservationRef().isPresent())
				throw new IOException("Invocation already has a different host observation");
			Intent intent = new Intent(1, requested, Base64.getEncoder().encodeToString(canonicalBytes));
			writeAtomically(intentFile, JSON.writeValueAsBytes(intent));
			recoverIntent();
			return reference;
		});
	}

	/**
	 * Resolves only a verified OPEN registration with its original one-use observation.
	 *
	 * @param store pinned campaign store
	 * @param binding exact campaign binding
	 * @param observationRef immutable observation reference
	 * @return original OPEN registration
	 * @throws IOException if the index or registration is missing, closed, foreign, or corrupt
	 */
	InvocationStopRegistration find(CampaignStore store, CampaignBinding binding,
		EvidenceRef observationRef) throws IOException
	{
		Objects.requireNonNull(store, "store");
		Objects.requireNonNull(binding, "binding");
		Objects.requireNonNull(observationRef, "observationRef");
		return locked(() ->
		{
			Association match = null;
			for (Association association : readIndex())
				if (association.observationRef().equals(observationRef))
				{
					if (match != null)
						throw new IOException("Host observation index is ambiguous");
					match = association;
				}
			if (match == null || !match.binding().equals(binding))
				throw new IOException("Host observation is not indexed for this campaign");
			return verifiedOpenRegistration(store, match).orElseThrow(() ->
				new IOException("Host observation registration is not OPEN or exact"));
		});
	}

	/**
	 * Resolves an interruption event to its original open registration across the workflow root.
	 *
	 * @param hostSessionId non-blank host session identity
	 * @param hostTurnId non-blank host turn identity
	 * @return matching open registration, or empty when none remains open
	 * @throws IOException if indexed host or campaign evidence conflicts
	 */
	java.util.Optional<InvocationStopRegistration> findOpenByHostTurn(String hostSessionId,
		String hostTurnId) throws IOException
	{
		if (hostSessionId == null || hostSessionId.isBlank() || hostTurnId == null || hostTurnId.isBlank())
			throw new IllegalArgumentException("Host session and turn IDs must be non-blank");
		return locked(() ->
		{
			for (Association association : readIndex())
				if (association.hostSessionId().equals(hostSessionId) &&
					association.hostTurnId().equals(hostTurnId))
				{
					CampaignStore owner = CampaignStore.open(association.binding().realDirectory());
					return verifiedOpenRegistration(owner, association);
				}
			return java.util.Optional.empty();
		});
	}

	/**
	 * Verifies the indexed observation and its original registration before exposing live authority.
	 *
	 * @param owner pinned owner store
	 * @param association global host observation association
	 * @return original open registration, or empty after it closes
	 * @throws IOException if retained identity or evidence conflicts
	 */
	private java.util.Optional<InvocationStopRegistration> verifiedOpenRegistration(CampaignStore owner,
		Association association) throws IOException
	{
		CampaignBinding binding = association.binding();
		Campaign campaign = owner.lockedSnapshot(binding);
		InvocationStopRegistration matched = null;
		for (InvocationStopRegistration registration : campaign.scheduler().invocations())
			if (registration.invocationId().equals(association.invocationId()) &&
				registration.registrationRef().equals(association.registrationRef()) &&
				registration.binding().equals(binding))
			{
				if (matched != null)
					throw new IOException("Host observation registration is ambiguous");
				matched = registration;
			}
		if (matched == null || !matched.hostObservationRef().equals(
			java.util.Optional.of(association.observationRef())))
			throw new IOException("Host observation index and registration disagree");
		Observation observation = parseObservation(owner.readImmutableJson(association.observationRef()));
		if (!association.tokenDigest().equals(observation.tokenDigest()) ||
			!association.hostSessionId().equals(observation.hostSessionId()) ||
			!association.hostTurnId().equals(observation.hostTurnId()))
			throw new IOException("Host observation index fields disagree with evidence");
		if (matched.status() == InvocationStopRegistration.Status.CLOSED)
			return java.util.Optional.empty();
		return java.util.Optional.of(matched);
	}

	/**
	 * Rolls a committed intent forward before lease or recovery lookup.
	 *
	 * @throws IOException if the intent or durable association is corrupt
	 */
	void recover() throws IOException
	{
		locked(() -> null);
	}

	/**
	 * Runs a state operation after rolling forward any committed host publication.
	 *
	 * @param action operation under the registry lock
	 * @param <T> result type
	 * @return operation result
	 * @throws IOException if recovery or the operation fails
	 */
	<T> T locked(RegistryAction<T> action) throws IOException
	{
		synchronized (HostObservationRegistry.class)
		{
			Files.createDirectories(workflowRoot);
			if (Files.isSymbolicLink(workflowRoot) || Files.isSymbolicLink(lockFile))
				throw new IOException("Host observation registry is redirected");
			try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
				StandardOpenOption.WRITE); FileLock lock = channel.lock())
			{
				if (!lock.isValid())
					throw new IOException("Host observation registry lock was lost");
				recoverIntent();
				return action.run();
			}
		}
	}

	/** Rolls a durable publication intent into both campaign and global index. */
	private void recoverIntent() throws IOException
	{
		if (Files.notExists(intentFile, LinkOption.NOFOLLOW_LINKS))
			return;
		Intent intent = readCanonical(intentFile, Intent.class);
		if (intent.schemaVersion() != 1 || intent.association() == null ||
			intent.observationBase64() == null)
			throw new IOException("Host observation intent schema is invalid");
		Association association = intent.association();
		validateAssociation(association);
		byte[] bytes;
		try
		{
			bytes = Base64.getDecoder().decode(intent.observationBase64());
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Host observation intent bytes are invalid", e);
		}
		Observation observation = parseObservation(bytes);
		if (!association.observationRef().equals(new EvidenceRef("host-interrupt-observations", sha256(bytes))) ||
			!association.tokenDigest().equals(observation.tokenDigest()) ||
			!association.hostSessionId().equals(observation.hostSessionId()) ||
			!association.hostTurnId().equals(observation.hostTurnId()))
			throw new IOException("Host observation intent and bytes disagree");
		if (!association.binding().realDirectory().getParent().equals(workflowRoot))
			throw new IOException("Host observation intent belongs to another workflow root");
		CampaignStore ownerStore = CampaignStore.open(association.binding().realDirectory());
		if (!ownerStore.binding().equals(association.binding()))
			throw new IOException("Host observation intent has a foreign campaign binding");
		List<Association> associations = readIndex();
		for (Association existing : associations)
			if (!existing.equals(association) && conflicts(existing, association))
				throw new IOException("Host observation identity is already used");
		InvocationStopRegistration registration = registration(ownerStore, association.binding(),
			association.invocationId(), association.registrationRef());
		if (registration.hostObservationRef().isPresent() &&
			!registration.hostObservationRef().orElseThrow().equals(association.observationRef()))
			throw new IOException("Invocation already has a different host observation");
		EvidenceRef persisted = ownerStore.appendImmutableJson("host-interrupt-observations", bytes);
		if (!persisted.equals(association.observationRef()))
			throw new IOException("Host observation reference changed during persistence");
		ownerStore.updateScheduler(association.binding(), campaign ->
		{
			List<InvocationStopRegistration> invocations = new ArrayList<>(campaign.scheduler().invocations());
			for (int index = 0; index < invocations.size(); ++index)
			{
				InvocationStopRegistration current = invocations.get(index);
				if (current.invocationId().equals(association.invocationId()) &&
					current.registrationRef().equals(association.registrationRef()) &&
					current.status() == InvocationStopRegistration.Status.OPEN)
				{
					if (current.hostObservationRef().isPresent() &&
						!current.hostObservationRef().orElseThrow().equals(persisted))
						throw new IOException("Invocation already has another host observation");
					invocations.set(index, new InvocationStopRegistration(current.registrationRef(),
						current.binding(), current.invocationId(), current.registeredCounter(),
						current.observedOwnerFence(), java.util.Optional.of(persisted), current.status(),
						current.boundFence()));
					return new SchedulerState(campaign.scheduler().fence(), invocations,
						campaign.scheduler().stops(), campaign.scheduler().slots(), campaign.scheduler().barrier());
				}
			}
			throw new IOException("Host observation registration closed before publication");
		});
		if (!associations.contains(association))
		{
			associations.add(association);
			writeAtomically(indexFile, JSON.writeValueAsBytes(new Index(1, associations)));
		}
		Files.delete(intentFile);
	}

	/**
	 * Finds the exact OPEN invocation registration.
	 *
	 * @param ownerStore pinned owner campaign store
	 * @param binding exact campaign binding
	 * @param invocationId original invocation id
	 * @param registrationRef original registration evidence
	 * @return OPEN registration
	 * @throws IOException if it is missing, closed, or foreign
	 */
	private InvocationStopRegistration registration(CampaignStore ownerStore, CampaignBinding binding,
		String invocationId,
		EvidenceRef registrationRef) throws IOException
	{
		Campaign campaign = ownerStore.lockedSnapshot(binding);
		for (InvocationStopRegistration registration : campaign.scheduler().invocations())
			if (registration.invocationId().equals(invocationId) &&
				registration.registrationRef().equals(registrationRef) && registration.binding().equals(binding) &&
				registration.status() == InvocationStopRegistration.Status.OPEN)
				return registration;
		throw new IOException("Host observation registration is not OPEN or exact");
	}

	/**
	 * Reads and validates the global one-use index.
	 *
	 * @return indexed associations
	 * @throws IOException if the index is malformed or ambiguous
	 */
	private List<Association> readIndex() throws IOException
	{
		if (Files.notExists(indexFile, LinkOption.NOFOLLOW_LINKS))
			return new ArrayList<>();
		Index index = readCanonical(indexFile, Index.class);
		if (index.schemaVersion() != 1 || index.entries() == null)
			throw new IOException("Host observation index schema is invalid");
		List<Association> entries = new ArrayList<>(index.entries());
		for (Association entry : entries)
			validateAssociation(entry);
		for (int first = 0; first < entries.size(); ++first)
			for (int second = first + 1; second < entries.size(); ++second)
				if (conflicts(entries.get(first), entries.get(second)))
					throw new IOException("Host observation index is ambiguous");
		return entries;
	}

	/**
	 * Validates one canonical global association before it is used as authority.
	 *
	 * @param association indexed association
	 * @throws IOException if its schema or identity is malformed or foreign
	 */
	private void validateAssociation(Association association) throws IOException
	{
		if (association == null || association.schemaVersion() != 1 || association.binding() == null ||
			!workflowRoot.equals(association.binding().realDirectory().getParent()) ||
			association.invocationId() == null || association.invocationId().isBlank() ||
			association.registrationRef() == null ||
			!association.registrationRef().collection().equals("invocation-stop-registrations") ||
			association.observationRef() == null ||
			!association.observationRef().collection().equals("host-interrupt-observations") ||
			association.tokenDigest() == null || !association.tokenDigest().matches("[0-9a-f]{64}") ||
			association.hostSessionId() == null || association.hostSessionId().isBlank() ||
			association.hostTurnId() == null || association.hostTurnId().isBlank())
			throw new IOException("Host observation association schema or identity is invalid");
	}

	/**
	 * Parses canonical schema-1 C9 observation bytes.
	 *
	 * @param bytes canonical observation bytes
	 * @return validated observation
	 * @throws IOException if bytes are malformed or noncanonical
	 */
	private Observation parseObservation(byte[] bytes) throws IOException
	{
		try
		{
			Observation observation = JSON.readValue(bytes, Observation.class);
			if (observation == null || observation.schemaVersion() != 1 ||
				observation.tokenDigest() == null || !observation.tokenDigest().matches("[0-9a-f]{64}") ||
				observation.hostSessionId() == null || observation.hostSessionId().isBlank() ||
				observation.hostTurnId() == null || observation.hostTurnId().isBlank() ||
				!Arrays.equals(bytes, JSON.writeValueAsBytes(observation)))
				throw new IOException("Host observation is noncanonical or has invalid schema");
			return observation;
		}
		catch (RuntimeException e)
		{
			throw new IOException("Host observation is malformed", e);
		}
	}

	/**
	 * Checks whether two associations reuse any one-use identity.
	 *
	 * @param first existing association
	 * @param second candidate association
	 * @return true if any one-use identity is shared
	 */
	private static boolean conflicts(Association first, Association second)
	{
		return first.tokenDigest().equals(second.tokenDigest()) ||
			first.hostSessionId().equals(second.hostSessionId()) &&
				first.hostTurnId().equals(second.hostTurnId()) ||
			first.observationRef().equals(second.observationRef()) ||
			first.binding().equals(second.binding()) && first.invocationId().equals(second.invocationId());
	}

	/**
	 * Reads a strict, canonical registry document.
	 *
	 * @param path registry file
	 * @param type document type
	 * @param <T> document type
	 * @return parsed document
	 * @throws IOException if it is malformed, noncanonical, or redirected
	 */
	private <T> T readCanonical(Path path, Class<T> type) throws IOException
	{
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Host observation registry file is redirected: " + path);
		try
		{
			byte[] bytes = Files.readAllBytes(path);
			T value = JSON.readValue(bytes, type);
			if (value == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(value)))
				throw new IOException("Host observation registry file is noncanonical: " + path);
			return value;
		}
		catch (RuntimeException e)
		{
			throw new IOException("Host observation registry file is malformed: " + path, e);
		}
	}

	/**
	 * Atomically replaces one registry document after forcing its bytes.
	 *
	 * @param target exact registry file
	 * @param bytes canonical document bytes
	 * @throws IOException if persistence fails
	 */
	private static void writeAtomically(Path target, byte[] bytes) throws IOException
	{
		if (Files.isSymbolicLink(target))
			throw new IOException("Host observation registry file is redirected: " + target);
		Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
		try
		{
			try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
				StandardOpenOption.WRITE))
			{
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				while (buffer.hasRemaining())
					channel.write(buffer);
				channel.force(true);
			}
			Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING);
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Computes the content address before publishing bytes.
	 *
	 * @param bytes canonical bytes
	 * @return lowercase SHA-256 digest
	 */
	private static String sha256(byte[] bytes)
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError(e);
		}
	}

	/**
	 * Runs one registry operation while the global process-independent lock is held.
	 *
	 * @param <T> operation result type
	 */
	@FunctionalInterface
	interface RegistryAction<T>
	{
		/**
		 * Runs a locked registry operation.
		 *
		 * @return operation result
		 * @throws IOException if it cannot finish
		 */
		T run() throws IOException;
	}

	private record Observation(int schemaVersion, String tokenDigest, String hostSessionId, String hostTurnId)
	{
	}

	private record Association(int schemaVersion, CampaignBinding binding, String invocationId,
		EvidenceRef registrationRef, EvidenceRef observationRef, String tokenDigest, String hostSessionId,
		String hostTurnId)
	{
	}

	private record Index(int schemaVersion, List<Association> entries)
	{
	}

	private record Intent(int schemaVersion, Association association, String observationBase64)
	{
	}
}
