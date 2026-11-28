package micheal65536.fountain.utils;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v425_genoa.serializer.GenoaItemAwardedNotificationSerializer_v425_Genoa;
import org.cloudburstmc.protocol.bedrock.data.GenoaItemAwardedNotification;
import org.cloudburstmc.protocol.bedrock.packet.GenoaItemAwardedNotificationPacket;

import java.util.UUID;

public final class GenoaItemAwardedNotificationSerializer extends GenoaItemAwardedNotificationSerializer_v425_Genoa
{
	public static final GenoaItemAwardedNotificationSerializer INSTANCE = new GenoaItemAwardedNotificationSerializer();

	@Override
	public void serialize(ByteBuf buffer, BedrockCodecHelper helper, GenoaItemAwardedNotificationPacket packet)
	{
		helper.writeString(buffer, packet.getString1());
		helper.writeString(buffer, packet.getString2());
		helper.writeString(buffer, packet.getString3());
		GenoaItemAwardedNotification[] items = packet.getArr() != null ? packet.getArr() : new GenoaItemAwardedNotification[0];
		buffer.writeIntLE(items.length);
		for (GenoaItemAwardedNotification item : items)
		{
			buffer.writeIntLE(item.getRuntimeEntityId());
			buffer.writeIntLE(item.getAmount());
			helper.writeUuid(buffer, item.getCatalogItemUuid());
		}
	}

	@Override
	public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, GenoaItemAwardedNotificationPacket packet)
	{
		packet.setString1(helper.readString(buffer));
		packet.setString2(helper.readString(buffer));
		packet.setString3(helper.readString(buffer));
		int count = buffer.readIntLE();
		GenoaItemAwardedNotification[] items = new GenoaItemAwardedNotification[Math.max(count, 0)];
		for (int index = 0; index < items.length; index++)
		{
			int kind = buffer.readIntLE();
			int amount = buffer.readIntLE();
			UUID uuid = helper.readUuid(buffer);
			items[index] = new GenoaItemAwardedNotification(kind, amount, uuid);
		}
		packet.setArr(items);
	}
}
