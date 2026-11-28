package micheal65536.fountain.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class AdventureChestLoot
{
	public static final String EVENT_TYPE = "adventureChestLoot";
	private static final String LINE_PREFIX = "1 ";

	public final String playerId;
	public final String rarity;
	public final Map<String, Integer> items;

	public AdventureChestLoot(@NotNull String playerId, @NotNull String rarity, @NotNull Map<String, Integer> items)
	{
		this.playerId = playerId;
		this.rarity = rarity;
		this.items = items;
	}

	@Nullable
	public static AdventureChestLoot parseBusLine(@NotNull String line)
	{
		if (!line.startsWith(LINE_PREFIX))
		{
			return null;
		}

		String[] fields = line.substring(LINE_PREFIX.length()).split(":", 3);
		if (fields.length != 3 || !fields[1].equals(EVENT_TYPE))
		{
			return null;
		}

		return parse(fields[2]);
	}

	@Nullable
	public static AdventureChestLoot parse(@NotNull String json)
	{
		try
		{
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			String playerId = root.get("playerId").getAsString();
			String rarity = root.has("rarity") && !root.get("rarity").isJsonNull() ? root.get("rarity").getAsString() : "";
			Map<String, Integer> items = new LinkedHashMap<>();
			JsonArray array = root.getAsJsonArray("items");
			for (JsonElement element : array)
			{
				JsonObject item = element.getAsJsonObject();
				String itemId = UUID.fromString(item.get("itemId").getAsString()).toString();
				int count = item.get("count").getAsInt();
				if (count >= 1)
				{
					items.merge(itemId, count, Integer::sum);
				}
			}
			return new AdventureChestLoot(playerId, rarity, items);
		}
		catch (RuntimeException exception)
		{
			return null;
		}
	}

	public static boolean samePlayer(@NotNull String first, @NotNull String second)
	{
		return normalizePlayerId(first).equals(normalizePlayerId(second));
	}

	private static String normalizePlayerId(String playerId)
	{
		return playerId.replace("-", "").toLowerCase(Locale.ROOT);
	}

	@Nullable
	public static String parseBusAddress(@Nullable String connectorPluginArg)
	{
		if (connectorPluginArg == null || connectorPluginArg.isBlank())
		{
			return null;
		}

		try
		{
			for (Map.Entry<String, JsonElement> entry : JsonParser.parseString(connectorPluginArg).getAsJsonObject().entrySet())
			{
				if (entry.getKey().equalsIgnoreCase("EventBusAddress") && entry.getValue().isJsonPrimitive())
				{
					String address = entry.getValue().getAsString().trim();
					return address.isEmpty() ? null : address;
				}
			}
		}
		catch (RuntimeException exception)
		{
			// empty
		}
		return null;
	}
}
