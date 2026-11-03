package com.holdmyspot.cat.common.updater.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Subscription;

/**
 * Copies one HTTP response into the caller's output, stopping before it exceeds the caller's byte limit.
 */
final class HttpsBodySubscriber implements BodySubscriber<Long>
{
	private final OutputStream output;
	private final long maximum;
	private final CompletableFuture<Long> result = new CompletableFuture<>();
	private Subscription subscription;
	private long count;

	/**
	 * Creates a bounded response consumer. The caller retains ownership of the output.
	 *
	 * @param output  response destination
	 * @param maximum maximum response size, in bytes
	 */
	HttpsBodySubscriber(OutputStream output, long maximum)
	{
		requireThat(output, "output").isNotNull();
		requireThat(maximum, "maximum").isGreaterThanOrEqualTo(0L);
		this.output = output;
		this.maximum = maximum;
	}

	@Override
	public CompletionStage<Long> getBody()
	{
		return result;
	}

	@Override
	public void onSubscribe(Subscription subscription)
	{
		this.subscription = subscription;
		subscription.request(1);
	}

	@Override
	public void onNext(List<ByteBuffer> buffers)
	{
		try
		{
			byte[] chunk = new byte[8192];
			for (ByteBuffer buffer : buffers)
			{
				if (buffer.remaining() > maximum - count)
					throw new IOException("The release download exceeds its maximum size of " + maximum + " bytes");
				while (buffer.hasRemaining())
				{
					int length = Math.min(buffer.remaining(), chunk.length);
					buffer.get(chunk, 0, length);
					output.write(chunk, 0, length);
					count += length;
				}
			}
			subscription.request(1);
		}
		catch (IOException failure)
		{
			subscription.cancel();
			result.completeExceptionally(failure);
		}
	}

	@Override
	public void onError(Throwable failure)
	{
		result.completeExceptionally(failure);
	}

	@Override
	public void onComplete()
	{
		result.complete(count);
	}
}
