package micheal65536.fountain.registry;

import com.google.gson.JsonElement;
import org.jetbrains.annotations.Nullable;

import micheal65536.fountain.DataFile;

import java.util.HashMap;
import java.util.Map;

public final class BlockBreakingHealth
{
	private static final HashMap<String, Integer> HEALTH = new HashMap<>();

	static
	{
		DataFile.load("registry/block_breaking_health.json", root ->
		{
			for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet())
			{
				HEALTH.put(entry.getKey(), entry.getValue().getAsInt());
			}
		});
	}

	private BlockBreakingHealth()
	{
	}

	public static int get(@Nullable String bedrockBlockName)
	{
		return bedrockBlockName != null ? HEALTH.getOrDefault(bedrockBlockName, 0) : 0;
	}
}
