package micheal65536.fountain;

import com.github.steveice10.mc.protocol.MinecraftProtocol;
import com.github.steveice10.mc.protocol.codec.MinecraftCodec;
import com.github.steveice10.mc.protocol.codec.MinecraftPacketSerializer;
import com.github.steveice10.mc.protocol.codec.PacketCodec;
import com.github.steveice10.mc.protocol.codec.PacketStateCodec;
import com.github.steveice10.mc.protocol.data.ProtocolState;
import com.github.steveice10.mc.protocol.packet.ingame.clientbound.entity.ClientboundRemoveMobEffectPacket;
import com.github.steveice10.mc.protocol.packet.ingame.clientbound.entity.ClientboundUpdateMobEffectPacket;
import com.github.steveice10.mc.protocol.packet.ingame.clientbound.entity.spawn.ClientboundAddEntityPacket;
import com.github.steveice10.packetlib.codec.PacketDefinition;
import com.github.steveice10.packetlib.packet.Packet;
import com.github.steveice10.packetlib.packet.PacketProtocol;
import com.github.steveice10.packetlib.tcp.TcpClientSession;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.apache.logging.log4j.LogManager;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v425_genoa.Bedrock_v425_Genoa;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.GenoaDisconnectIdPacket;
import org.cloudburstmc.protocol.bedrock.packet.GenoaUpdateBlockPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelSoundEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.MapCreateLockedCopyPacket;
import org.cloudburstmc.protocol.bedrock.packet.OnScreenTextureAnimationPacket;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import micheal65536.fountain.connector.PlayerConnectorPluginWrapper;
import micheal65536.fountain.connector.plugin.BindPlayerResult;
import micheal65536.fountain.connector.plugin.ConnectorPlugin;
import micheal65536.fountain.connector.plugin.PlayerLoginInfo;
import micheal65536.fountain.connector.plugin.ResourceLocation;
import micheal65536.fountain.utils.GenoaDisconnectIdSerializer;
import micheal65536.fountain.utils.GenoaUpdateBlockDamageSerializer;
import micheal65536.fountain.utils.LoginUtils;
import micheal65536.fountain.utils.TolerantLevelSoundEventSerializer;

import java.lang.reflect.Field;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

public class SessionsManager
{
	private static final PacketCodec MINECRAFT_CODEC_WITH_CUSTOM_ENTITY_SUPPORT = createCustomCodec();
	private static final BedrockCodec BEDROCK_CODEC = Bedrock_v425_Genoa.CODEC.toBuilder()
			.deregisterPacket(OnScreenTextureAnimationPacket.class)
			.deregisterPacket(MapCreateLockedCopyPacket.class)
			.updateSerializer(GenoaDisconnectIdPacket.class, GenoaDisconnectIdSerializer.INSTANCE)
			.updateSerializer(LevelSoundEventPacket.class, TolerantLevelSoundEventSerializer.from(Bedrock_v425_Genoa.CODEC))
			.updateSerializer(GenoaUpdateBlockPacket.class, GenoaUpdateBlockDamageSerializer.INSTANCE)
			.build();

	private final String serverAddress;
	private final int serverPort;
	private final ConnectorPlugin connectorPlugin;
	private final boolean useUUIDAsUsername;

	private final ReentrantLock lock = new ReentrantLock(true);

	private boolean acceptNewConnection = true;
	private final HashSet<LoginBedrockPacketHandler> pendingSessions = new HashSet<>();
	private final HashSet<ActiveSession> activeSessions = new HashSet<>();
	private final HashSet<ClosedSessionBedrockPacketHandler> closedSessions = new HashSet<>();

	// V2 dimension/instance routing
	private final HashMap<String, ResourceLocation> instanceIdToDimensionKey = new HashMap<>();
	private final HashMap<String, String> playerUuidToInstanceId = new HashMap<>();

	public SessionsManager(@NotNull String serverAddress, int serverPort, @NotNull ConnectorPlugin connectorPlugin, boolean useUUIDAsUsername)
	{
		this.serverAddress = serverAddress;
		this.serverPort = serverPort;
		this.connectorPlugin = connectorPlugin;
		this.useUUIDAsUsername = useUUIDAsUsername;
	}

	public void newClientConnection(@NotNull BedrockServerSession bedrockServerSession)
	{
		this.lock.lock();

		LogManager.getLogger().info("New connection from {}", bedrockServerSession.getPeer().getSocketAddress());

		if (!this.acceptNewConnection)
		{
			LogManager.getLogger().info("Rejecting connection as we are shutting down");
			this.lock.unlock();
			return;
		}

		bedrockServerSession.setCodec(BEDROCK_CODEC);
		bedrockServerSession.getPeer().getCodecHelper().setBlockDefinitions(Main.BLOCK_DEFINITION_REGISTRY);
		bedrockServerSession.getPeer().getCodecHelper().setItemDefinitions(Main.ITEM_DEFINITION_REGISTRY);

		LoginBedrockPacketHandler loginBedrockPacketHandler = new LoginBedrockPacketHandler(bedrockServerSession);
		this.pendingSessions.add(loginBedrockPacketHandler);
		bedrockServerSession.setPacketHandler(loginBedrockPacketHandler);

		this.lock.unlock();
	}

	private void handleLogin(@NotNull LoginBedrockPacketHandler loginBedrockPacketHandler, @NotNull LoginPacket loginPacket)
	{
		this.lock.lock();

		LoginUtils.LoginInfo loginInfo = LoginUtils.getLoginInfo(loginPacket);
		if (loginInfo == null)
		{
			LogManager.getLogger().warn("Could not get login info from login packet");
			this.disconnectPending(loginBedrockPacketHandler);
			this.lock.unlock();
			return;
		}
		if (this.activeSessions.stream().anyMatch(activeSession -> activeSession.uuid.equals(loginInfo.uuid)))
		{
			LogManager.getLogger().warn("Multiple player logins with the same UUID {} {}", loginInfo.username, loginInfo.uuid);
			this.disconnectPending(loginBedrockPacketHandler);
			this.lock.unlock();
			return;
		}
		try
		{
			// v1 backward compatibility: let the connector plugin accept/reject the player
			boolean accepted = this.connectorPlugin.onPlayerConnected(new PlayerLoginInfo(loginInfo.uuid));
			if (!accepted)
			{
				LogManager.getLogger().warn("Connector plugin rejected player login for {} {}", loginInfo.username, loginInfo.uuid);
				this.disconnectPending(loginBedrockPacketHandler);
				this.lock.unlock();
				return;
			}
		}
		catch (ConnectorPlugin.ConnectorPluginException exception)
		{
			LogManager.getLogger().warn("Connector plugin threw exception when handling player login for {} {}", loginInfo.username, loginInfo.uuid, exception);
			this.disconnectPending(loginBedrockPacketHandler);
			this.lock.unlock();
			return;
		}

		// --- V2: resolve instance and dimension for this player ---
		// 1. Check pre-configured player→instance binding
		String instanceId = this.playerUuidToInstanceId.getOrDefault(loginInfo.uuid, null);

		// 2. If no pre-binding, default to "default" shared instance
		if (instanceId == null)
		{
			instanceId = "default";
		}

		int dimensionId = 0;          // default: overworld
		ResourceLocation dimensionKey = null;
		String resolvedInstanceId = instanceId;

		try
		{
			BindPlayerResult bindResult = this.connectorPlugin.bindPlayerToInstance(loginInfo.uuid, instanceId)
					.get(5, TimeUnit.SECONDS);

			if (!bindResult.success)
			{
				LogManager.getLogger().warn("Connector plugin failed to bind player {} to instance {}: {}",
						loginInfo.username, instanceId, bindResult.error);
				this.disconnectPending(loginBedrockPacketHandler);
				this.lock.unlock();
				return;
			}

			dimensionId = bindResult.dimensionId;
			// Resolve dimension key from the pre-registered mapping if available
			ResourceLocation mappedKey = this.instanceIdToDimensionKey.getOrDefault(instanceId, null);
			if (mappedKey != null)
			{
				dimensionKey = mappedKey;
			}
			else
			{
				// Fallback: construct a dimension key from the instance ID
				dimensionKey = ResourceLocation.fromString("fountain:" + instanceId);
			}
			resolvedInstanceId = instanceId;

			LogManager.getLogger().info("Bound player {} ({}) to instance {} dimensionId {} dimensionKey {}",
					loginInfo.username, loginInfo.uuid, resolvedInstanceId, dimensionId, dimensionKey);
		}
		catch (ConnectorPlugin.ConnectorPluginException exception)
		{
			LogManager.getLogger().warn("Connector plugin threw exception when binding player {} to instance {}",
					loginInfo.username, instanceId, exception);
			this.disconnectPending(loginBedrockPacketHandler);
			this.lock.unlock();
			return;
		}
		catch (InterruptedException exception)
		{
			LogManager.getLogger().warn("Interrupted while binding player {} to instance {}",
					loginInfo.username, instanceId);
			Thread.currentThread().interrupt();
			this.disconnectPending(loginBedrockPacketHandler);
			this.lock.unlock();
			return;
		}
		catch (ExecutionException exception)
		{
			LogManager.getLogger().warn("Execution exception while binding player {} to instance {}",
					loginInfo.username, instanceId, exception);
			this.disconnectPending(loginBedrockPacketHandler);
			this.lock.unlock();
			return;
		}
		catch (TimeoutException exception)
		{
			LogManager.getLogger().warn("Timeout while binding player {} to instance {} after 5 seconds",
					loginInfo.username, instanceId);
			this.disconnectPending(loginBedrockPacketHandler);
			this.lock.unlock();
			return;
		}

		this.pendingSessions.remove(loginBedrockPacketHandler);
		LogManager.getLogger().info("Player logged in {} {}", loginInfo.username, loginInfo.uuid);

		MinecraftProtocol javaProtocol = new MinecraftProtocol(MINECRAFT_CODEC_WITH_CUSTOM_ENTITY_SUPPORT, this.useUUIDAsUsername ? loginInfo.uuid : loginInfo.username);
		TcpClientSession tcpClientSession = new TcpClientSession(this.serverAddress, this.serverPort, javaProtocol);

		PlayerSession playerSession = new PlayerSession(loginBedrockPacketHandler.bedrockServerSession, tcpClientSession, new PlayerConnectorPluginWrapper(this.connectorPlugin, loginInfo.uuid), this::onSessionClosed);
		playerSession.setAssignedDimension(dimensionKey, dimensionId);
		this.activeSessions.add(new ActiveSession(loginInfo.uuid, resolvedInstanceId, playerSession, loginBedrockPacketHandler.bedrockServerSession));

		playerSession.mutex.lock();
		loginBedrockPacketHandler.bedrockServerSession.setPacketHandler(new ClientPacketHandler(playerSession));
		tcpClientSession.addListener(new ServerPacketHandler(playerSession));
		tcpClientSession.connect(true);
		playerSession.mutex.unlock();

		this.lock.unlock();
	}

	private void onSessionClosed(PlayerSession playerSession)
	{
		this.lock.lock();
		ActiveSession activeSession = this.activeSessions.stream().filter(activeSession1 -> activeSession1.playerSession == playerSession).findAny().orElse(null);
		if (activeSession != null)
		{
			this.activeSessions.remove(activeSession);

			if (activeSession.bedrockServerSession.isConnected())
			{
				ClosedSessionBedrockPacketHandler closedSessionBedrockPacketHandler = new ClosedSessionBedrockPacketHandler(activeSession.bedrockServerSession);
				this.closedSessions.add(closedSessionBedrockPacketHandler);
				activeSession.bedrockServerSession.setPacketHandler(closedSessionBedrockPacketHandler);
			}

			LogManager.getLogger().info("Session has been closed {}", activeSession.uuid);
		}
		this.lock.unlock();
	}

	private void disconnectPending(@NotNull LoginBedrockPacketHandler loginBedrockPacketHandler)
	{
		this.lock.lock();
		if (this.pendingSessions.remove(loginBedrockPacketHandler))
		{
			LogManager.getLogger().info("Pending session has disconnected");
			try
			{
				loginBedrockPacketHandler.bedrockServerSession.disconnect();
			}
			catch (IllegalStateException exception)
			{
				// empty
			}
		}
		this.lock.unlock();
	}

	private void disconnectClosed(@NotNull ClosedSessionBedrockPacketHandler closedSessionBedrockPacketHandler)
	{
		this.lock.lock();
		if (this.closedSessions.remove(closedSessionBedrockPacketHandler))
		{
			LogManager.getLogger().info("Closed session has finished disconnecting");
			try
			{
				closedSessionBedrockPacketHandler.bedrockServerSession.disconnect();
			}
			catch (IllegalStateException exception)
			{
				// empty
			}
		}
		this.lock.unlock();
	}

	public void shutdown()
	{
		this.lock.lock();

		LogManager.getLogger().info("Shutting down");

		this.acceptNewConnection = false;

		LoginBedrockPacketHandler[] pendingSessions = this.pendingSessions.toArray(new LoginBedrockPacketHandler[0]);
		ActiveSession[] activeSessions = this.activeSessions.toArray(new ActiveSession[0]);

		this.lock.unlock();

		LogManager.getLogger().info("Disconnecting {} remaining pending sessions", pendingSessions.length);
		for (LoginBedrockPacketHandler loginBedrockPacketHandler : pendingSessions)
		{
			loginBedrockPacketHandler.bedrockServerSession.disconnect("", true);
		}

		LogManager.getLogger().info("Disconnecting {} remaining active sessions", activeSessions.length);
		for (ActiveSession activeSession : activeSessions)
		{
			activeSession.playerSession.mutex.lock();
			activeSession.playerSession.disconnect(true);
			activeSession.playerSession.mutex.unlock();
		}

		this.lock.lock();
		if (!this.closedSessions.isEmpty())
		{
			LogManager.getLogger().info("Waiting 20 seconds for {} remaining closed sessions to disconnect", this.closedSessions.size());
			long waitStartTime = System.nanoTime();
			while (!this.closedSessions.isEmpty() && System.nanoTime() < waitStartTime + 20 * 1000000000l)
			{
				this.lock.unlock();

				try
				{
					Thread.sleep(1000);
				}
				catch (InterruptedException exception)
				{
					// empty
				}

				this.lock.lock();
			}

			ClosedSessionBedrockPacketHandler[] closedSessions = this.closedSessions.toArray(new ClosedSessionBedrockPacketHandler[0]);
			this.closedSessions.clear();
			this.lock.unlock();

			if (closedSessions.length > 0)
			{
				LogManager.getLogger().info("Forcibly disconnecting {} remaining closed sessions", closedSessions.length);
				for (ClosedSessionBedrockPacketHandler closedSessionBedrockPacketHandler : closedSessions)
				{
					closedSessionBedrockPacketHandler.bedrockServerSession.disconnect("", true);
				}
			}

			this.lock.lock();
		}
		else
		{
			LogManager.getLogger().info("No remaining closed sessions to disconnect");
		}

		LogManager.getLogger().info("Shutdown complete");

		this.lock.unlock();
	}

	// --- V2 Dimension / Instance Routing API ---

	/**
	 * Registers an instance mapping: maps an orchestrator-level instanceId to a
	 * {@link ResourceLocation} dimension key. Called by the bridge when it loads
	 * a dimension mapping config file.
	 *
	 * TODO: The numeric {@code dimensionId} is intentionally not stored here. The
	 * connector plugin's {@code bindPlayerToInstance} result is the single source of
	 * truth for the dimension ID used during login routing; this method only maintains
	 * the dimension key needed to build the {@code StartGamePacket}. The
	 * {@code dimensionId} parameter is kept for API compatibility with the config file
	 * format (Main#loadDimensionMappings forwards it to the connector plugin, which
	 * registers it on its own side).
	 */
	public void updateInstanceDimensionMapping(@NotNull String instanceId, @NotNull ResourceLocation dimensionKey, int dimensionId)
	{
		this.lock.lock();
		try
		{
			this.instanceIdToDimensionKey.put(instanceId, dimensionKey);
			LogManager.getLogger().info("Updated instance mapping: instanceId={} dimensionKey={} dimensionId={}", instanceId, dimensionKey, dimensionId);
		}
		finally
		{
			this.lock.unlock();
		}
	}

	/**
	 * Pre-binds a player UUID to a specific instance. When that player connects,
	 * the bridge will route them to the bound instance without needing a join code.
	 */
	public void preBindPlayerToInstance(@NotNull String playerUuid, @NotNull String instanceId)
	{
		this.lock.lock();
		try
		{
			this.playerUuidToInstanceId.put(playerUuid, instanceId);
			LogManager.getLogger().info("Pre-bound player {} to instance {}", playerUuid, instanceId);
		}
		finally
		{
			this.lock.unlock();
		}
	}

	/**
	 * Returns the {@link ResourceLocation} dimension key for the given instance,
	 * or {@code null} if not found.
	 */
	@Nullable
	public ResourceLocation getDimensionKeyForInstance(@NotNull String instanceId)
	{
		this.lock.lock();
		try
		{
			return this.instanceIdToDimensionKey.getOrDefault(instanceId, null);
		}
		finally
		{
			this.lock.unlock();
		}
	}

	/**
	 * Returns the instanceId for the given player UUID if a pre-binding exists,
	 * or {@code null} otherwise.
	 */
	@Nullable
	public String getInstanceIdForPlayer(@NotNull String playerUuid)
	{
		this.lock.lock();
		try
		{
			return this.playerUuidToInstanceId.getOrDefault(playerUuid, null);
		}
		finally
		{
			this.lock.unlock();
		}
	}

	private class ActiveSession
	{
		private final String uuid;
		private final String instanceId;
		private final PlayerSession playerSession;
		private final BedrockServerSession bedrockServerSession;

		public ActiveSession(String uuid, String instanceId, PlayerSession playerSession, BedrockServerSession bedrockServerSession)
		{
			this.uuid = uuid;
			this.instanceId = instanceId;
			this.playerSession = playerSession;
			this.bedrockServerSession = bedrockServerSession;
		}
	}

	private class LoginBedrockPacketHandler implements BedrockPacketHandler
	{
		private final BedrockServerSession bedrockServerSession;

		public LoginBedrockPacketHandler(BedrockServerSession bedrockServerSession)
		{
			this.bedrockServerSession = bedrockServerSession;
		}

		@Override
		public PacketSignal handlePacket(BedrockPacket packet)
		{
			if (packet instanceof LoginPacket)
			{
				SessionsManager.this.handleLogin(this, (LoginPacket) packet);
			}
			else
			{
				LogManager.getLogger().warn("Received non-login packet during login phase");
				SessionsManager.this.disconnectPending(this);
			}
			return PacketSignal.HANDLED;
		}

		@Override
		public void onDisconnect(String reason)
		{
			LogManager.getLogger().info("Client disconnected during login phase: {}", reason);
			SessionsManager.this.disconnectPending(this);
		}
	}

	private class ClosedSessionBedrockPacketHandler implements BedrockPacketHandler
	{
		private final BedrockServerSession bedrockServerSession;

		public ClosedSessionBedrockPacketHandler(BedrockServerSession bedrockServerSession)
		{
			this.bedrockServerSession = bedrockServerSession;
		}

		@Override
		public PacketSignal handlePacket(BedrockPacket packet)
		{
			LogManager.getLogger().warn("Received packet after close: {}", packet.getClass().getSimpleName());
			return PacketSignal.HANDLED;
		}

		@Override
		public void onDisconnect(String reason)
		{
			LogManager.getLogger().info("Client has finished disconnecting: {}", reason);
			SessionsManager.this.disconnectClosed(this);
		}
	}

	private static PacketCodec createCustomCodec()
	{
		try
		{
			PacketCodec.Builder packetCodecBuilder = MinecraftCodec.CODEC.toBuilder();

			// this ugly mess is to allow replacing existing packets with our custom subclasses that allow for non-vanilla IDs/content
			Field stateProtocolsField = PacketCodec.Builder.class.getDeclaredField("stateProtocols");
			stateProtocolsField.setAccessible(true);
			EnumMap<ProtocolState, PacketStateCodec> stateProtocols = (EnumMap<ProtocolState, PacketStateCodec>) stateProtocolsField.get(packetCodecBuilder);
			PacketStateCodec packetStateCodec = stateProtocols.get(ProtocolState.GAME);
			Field clientboundField = PacketProtocol.class.getDeclaredField("clientbound");
			Field clientboundIdsField = PacketProtocol.class.getDeclaredField("clientboundIds");
			clientboundField.setAccessible(true);
			clientboundIdsField.setAccessible(true);
			Int2ObjectMap<PacketDefinition<? extends Packet, ?>> clientbound = (Int2ObjectMap<PacketDefinition<? extends Packet, ?>>) clientboundField.get(packetStateCodec);
			Map<Class<? extends Packet>, Integer> clientboundIds = (Map<Class<? extends Packet>, Integer>) clientboundIdsField.get(packetStateCodec);

			int id = clientboundIds.get(ClientboundAddEntityPacket.class);
			clientbound.put(id, new PacketDefinition<>(id, ClientboundAddEntityCustomPacket.class, new MinecraftPacketSerializer<>(ClientboundAddEntityCustomPacket::read)));
			clientboundIds.put(ClientboundAddEntityCustomPacket.class, id);

			id = clientboundIds.get(ClientboundUpdateMobEffectPacket.class);
			clientbound.put(id, new PacketDefinition<>(id, ClientboundUpdateMobEffectCustomPacket.class, new MinecraftPacketSerializer<>(ClientboundUpdateMobEffectCustomPacket::read)));
			clientboundIds.put(ClientboundUpdateMobEffectCustomPacket.class, id);

			id = clientboundIds.get(ClientboundRemoveMobEffectPacket.class);
			clientbound.put(id, new PacketDefinition<>(id, ClientboundRemoveMobEffectCustomPacket.class, new MinecraftPacketSerializer<>(ClientboundRemoveMobEffectCustomPacket::read)));
			clientboundIds.put(ClientboundRemoveMobEffectCustomPacket.class, id);

			return packetCodecBuilder.build();
		}
		catch (Exception exception)
		{
			throw new AssertionError(exception);
		}
	}
}