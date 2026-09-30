package io.github.cowwoc.cat.common.optimizer.runner;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
/**
 * Finalization receipt proving that a native semantic turn was never dispatched.
 * Schema one uses length-prefixed UTF-8 fields and one explicit run-ID presence byte.
 *
 * @param schemaVersion exactly one
 * @param logicalCallId the logical call finalized before dispatch
 * @param runId the native run identity if a session was started
 * @param finalizedAt when the no-dispatch decision was finalized
 */
public record NativeDispatchCompletion(int schemaVersion, String logicalCallId, Optional<String> runId,
    Instant finalizedAt)
{
    /**
     * Validates the schema version and receipt identity.
     */
    public NativeDispatchCompletion
    {
        if (schemaVersion != 1) throw new IllegalArgumentException("schemaVersion must be 1");
        RunnerIdentity.ObservationRules.check(logicalCallId, "logicalCallId");
        Objects.requireNonNull(runId, "runId");
        runId.ifPresent(value -> RunnerIdentity.ObservationRules.check(value, "runId"));
        Objects.requireNonNull(finalizedAt, "finalizedAt");
    }

    /**
     * Encodes the canonical receipt used as content-addressed evidence.
     *
     * @return the canonical receipt bytes
     */
    public byte[] bytes()
    {
        try
        {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(buffer);
            output.writeInt(schemaVersion);
            write(output, logicalCallId);
            output.writeBoolean(runId.isPresent());
            if (runId.isPresent()) write(output, runId.orElseThrow());
            write(output, finalizedAt.toString());
            output.flush();
            return buffer.toByteArray();
        }
        catch (IOException exception)
        {
            throw new AssertionError(exception);
        }
    }

    /**
     * Decodes only an exact canonical schema-one receipt.
     *
     * @param bytes the retained receipt bytes
     * @return the validated receipt
     * @throws IOException if the bytes are incomplete, malformed or noncanonical
     */
    public static NativeDispatchCompletion fromBytes(byte[] bytes) throws IOException
    {
        Objects.requireNonNull(bytes, "bytes");
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
        int version = input.readInt();
        String callId = read(input);
        Optional<String> run = Optional.empty();
        if (input.readBoolean())
            run = Optional.of(read(input));
        Instant time;
        try
        {
            time = Instant.parse(read(input));
        }
        catch (RuntimeException exception)
        {
            throw new IOException("invalid finalization time", exception);
        }
        if (input.available() != 0) throw new IOException("trailing receipt bytes");
        try
        {
            NativeDispatchCompletion receipt = new NativeDispatchCompletion(version, callId, run, time);
            if (!Arrays.equals(bytes, receipt.bytes())) throw new IOException("noncanonical receipt bytes");
            return receipt;
        }
        catch (RuntimeException exception)
        {
            throw new IOException("invalid completion receipt", exception);
        }
    }

    /**
     * Writes one length-prefixed UTF-8 field shared by schema-one receipts.
     *
     * @param output the binary output
     * @param value the field value
     * @throws IOException if output fails
     */
    static void write(DataOutputStream output, String value) throws IOException
    {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    /**
     * Reads one bounded length-prefixed UTF-8 field.
     *
     * @param input the binary input
     * @return the decoded field
     * @throws IOException if the field is truncated or invalid
     */
    static String read(DataInputStream input) throws IOException
    {
        int length = input.readInt();
        if (length < 0 || length > 1_048_576) throw new IOException("invalid field length");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("truncated field");
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
