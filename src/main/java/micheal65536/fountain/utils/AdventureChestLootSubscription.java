package micheal65536.fountain.utils;

import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public final class AdventureChestLootSubscription implements Closeable
{
	private static final int DEFAULT_PORT = 5532;
	private static final int CONNECT_TIMEOUT_MILLIS = 5000;
	private static final long RETRY_DELAY_MILLIS = 5000;
	private static final int MAX_LINE_LENGTH = 65536;

	private final String address;
	private final String queueName;
	private final Consumer<AdventureChestLoot> consumer;
	private final Thread thread;
	private volatile boolean closed = false;
	private volatile Socket socket = null;

	public AdventureChestLootSubscription(@NotNull String address, @NotNull String instanceId, @NotNull Consumer<AdventureChestLoot> consumer)
	{
		this.address = address;
		this.queueName = "buildplate_" + instanceId;
		this.consumer = consumer;
		this.thread = new Thread(this::run, "Adventure chest loot " + instanceId);
		this.thread.setDaemon(true);
		this.thread.start();
	}

	@Override
	public void close()
	{
		this.closed = true;
		this.thread.interrupt();
		Socket current = this.socket;
		if (current != null)
		{
			try
			{
				current.close();
			}
			catch (IOException exception)
			{
				// empty
			}
		}
	}

	private void run()
	{
		boolean warned = false;
		while (!this.closed)
		{
			try (Socket current = connect(this.address))
			{
				this.socket = current;
				OutputStream outputStream = current.getOutputStream();
				outputStream.write(("1 SUB " + this.queueName + "\n").getBytes(StandardCharsets.US_ASCII));
				outputStream.flush();
				warned = false;
				LogManager.getLogger().info("Following adventure chest loot on {} at {}", this.queueName, this.address);
				this.follow(new BufferedInputStream(current.getInputStream()));
			}
			catch (IOException exception)
			{
				if (!this.closed && !warned)
				{
					LogManager.getLogger().warn("Lost the event bus at {} ({}), adventure chest loot is not shown on the end screen until it is back", this.address, exception.toString());
				}
				warned = true;
			}

			if (this.closed)
			{
				return;
			}

			try
			{
				Thread.sleep(RETRY_DELAY_MILLIS);
			}
			catch (InterruptedException exception)
			{
				return;
			}
		}
	}

	private void follow(InputStream inputStream) throws IOException
	{
		for (;;)
		{
			String line = readLine(inputStream);
			if (line == null)
			{
				throw new IOException("Connection closed by the event bus");
			}
			if (line.equals("1 ERR"))
			{
				throw new IOException("The event bus ended the subscription");
			}

			AdventureChestLoot loot = AdventureChestLoot.parseBusLine(line);
			if (loot != null)
			{
				try
				{
					this.consumer.accept(loot);
				}
				catch (RuntimeException exception)
				{
					LogManager.getLogger().warn("Could not use adventure chest loot", exception);
				}
			}
		}
	}

	private static Socket connect(String address) throws IOException
	{
		String[] parts = address.split(":", 2);
		int port = DEFAULT_PORT;
		if (parts.length > 1)
		{
			try
			{
				port = Integer.parseInt(parts[1].trim());
			}
			catch (NumberFormatException exception)
			{
				port = DEFAULT_PORT;
			}
		}
		if (port <= 0 || port > 65535)
		{
			throw new IOException("Port number out of range in " + address);
		}

		IOException lastException = null;
		for (InetAddress inetAddress : InetAddress.getAllByName(parts[0].trim()))
		{
			Socket socket = new Socket();
			try
			{
				socket.connect(new InetSocketAddress(inetAddress, port), CONNECT_TIMEOUT_MILLIS);
				return socket;
			}
			catch (IOException exception)
			{
				socket.close();
				lastException = exception;
			}
		}
		throw lastException != null ? lastException : new IOException("No address for " + parts[0]);
	}

	@Nullable
	private static String readLine(InputStream inputStream) throws IOException
	{
		ByteArrayOutputStream line = new ByteArrayOutputStream();
		for (;;)
		{
			int value = inputStream.read();
			if (value == -1)
			{
				return null;
			}
			if (value == '\n')
			{
				return line.toString(StandardCharsets.US_ASCII);
			}
			if (line.size() >= MAX_LINE_LENGTH)
			{
				throw new IOException("Event bus line is too long");
			}
			line.write(value);
		}
	}
}
