package micheal65536.fountain.utils;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.BedrockPacketSerializer;
import org.cloudburstmc.protocol.bedrock.packet.GenoaUpdateBlockPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.util.Set;

public final class GenoaUpdateBlockDamageSerializer implements BedrockPacketSerializer<GenoaUpdateBlockPacket>
{
	public static final GenoaUpdateBlockDamageSerializer INSTANCE = new GenoaUpdateBlockDamageSerializer();

	private GenoaUpdateBlockDamageSerializer()
	{
	}

	@Override
	public void serialize(ByteBuf buffer, BedrockCodecHelper helper, GenoaUpdateBlockPacket packet)
	{
		helper.writeBlockPosition(buffer, packet.getBlockPosition());
		VarInts.writeUnsignedInt(buffer, packet.getRuntimeId());
		int flagValue = 0;
		for (UpdateBlockPacket.Flag flag : packet.getFlags())
		{
			flagValue |= (1 << flag.ordinal());
		}
		VarInts.writeUnsignedInt(buffer, flagValue);
		VarInts.writeUnsignedInt(buffer, packet.getDataLayer());
		if (packet.isCheckForFloat())
		{
			buffer.writeFloatLE(packet.getFloat1());
			buffer.writeBoolean(true);
		}
	}

	@Override
	public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, GenoaUpdateBlockPacket packet)
	{
		packet.setBlockPosition(helper.readBlockPosition(buffer));
		packet.setRuntimeId(VarInts.readUnsignedInt(buffer));
		int flagValue = VarInts.readUnsignedInt(buffer);
		Set<UpdateBlockPacket.Flag> flags = packet.getFlags();
		for (UpdateBlockPacket.Flag flag : UpdateBlockPacket.Flag.values())
		{
			if ((flagValue & (1 << flag.ordinal())) != 0)
			{
				flags.add(flag);
			}
		}
		packet.setDataLayer(VarInts.readUnsignedInt(buffer));
		if (buffer.readableBytes() != 0)
		{
			packet.setFloat1(buffer.readFloatLE());
			packet.setCheckForFloat(buffer.readBoolean());
		}
		else
		{
			packet.setCheckForFloat(false);
		}
	}
}
