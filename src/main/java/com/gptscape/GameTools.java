package com.gptscape;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

/**
 * Consultas somente leitura à conta do jogador (quests, diários, equipamento, banco...), oferecidas ao Gemini como
 * ferramentas. Só existem quando o usuário ativa "Share Account Details" / "Share Bank", e cada uma só roda quando o
 * modelo a pede para responder uma pergunta. Nunca interagem com o jogo nem enviam o nome do personagem.
 */
@Slf4j
@Singleton
public class GameTools
{
	private static final String PREFIX = "account_";
	private static final String NOT_LOGGED_IN = "The player is not logged in to the game right now.";
	private static final int MAX_BANK_ITEMS = 200;
	/** Tarefa "Bosses", de [proc,helper_slayer_current_assignment]. */
	private static final int SLAYER_BOSS_TASK = 98;

	private static final String[] ACCOUNT_TYPES = {
		"Regular", "Ironman", "Ultimate Ironman", "Hardcore Ironman", "Group Ironman", "Hardcore Group Ironman",
		"Unranked Group Ironman"
	};
	private static final String[] SPELLBOOKS = {"Standard", "Ancient Magicks", "Lunar", "Arceuus"};
	private static final String[] DIARY_TIERS = {"Easy", "Medium", "Hard", "Elite"};
	private static final String[] DIARY_AREAS = {
		"Ardougne", "Desert", "Falador", "Fremennik", "Kandarin", "Karamja", "Kourend & Kebos", "Lumbridge & Draynor",
		"Morytania", "Varrock", "Western Provinces", "Wilderness"
	};
	/** Varbits de conclusão (easy, medium, hard, elite), na ordem de {@link #DIARY_AREAS}. */
	private static final int[][] DIARY_VARBITS = {
		{VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE,
			VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE},
		{VarbitID.DESERT_DIARY_EASY_COMPLETE, VarbitID.DESERT_DIARY_MEDIUM_COMPLETE,
			VarbitID.DESERT_DIARY_HARD_COMPLETE, VarbitID.DESERT_DIARY_ELITE_COMPLETE},
		{VarbitID.FALADOR_DIARY_EASY_COMPLETE, VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE,
			VarbitID.FALADOR_DIARY_HARD_COMPLETE, VarbitID.FALADOR_DIARY_ELITE_COMPLETE},
		{VarbitID.FREMENNIK_DIARY_EASY_COMPLETE, VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE,
			VarbitID.FREMENNIK_DIARY_HARD_COMPLETE, VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE},
		{VarbitID.KANDARIN_DIARY_EASY_COMPLETE, VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE,
			VarbitID.KANDARIN_DIARY_HARD_COMPLETE, VarbitID.KANDARIN_DIARY_ELITE_COMPLETE},
		{VarbitID.ATJUN_EASY_DONE, VarbitID.ATJUN_MED_DONE,
			VarbitID.ATJUN_HARD_DONE, VarbitID.KARAMJA_DIARY_ELITE_COMPLETE},
		{VarbitID.KOUREND_DIARY_EASY_COMPLETE, VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE,
			VarbitID.KOUREND_DIARY_HARD_COMPLETE, VarbitID.KOUREND_DIARY_ELITE_COMPLETE},
		{VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE,
			VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE},
		{VarbitID.MORYTANIA_DIARY_EASY_COMPLETE, VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE,
			VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE},
		{VarbitID.VARROCK_DIARY_EASY_COMPLETE, VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE,
			VarbitID.VARROCK_DIARY_HARD_COMPLETE, VarbitID.VARROCK_DIARY_ELITE_COMPLETE},
		{VarbitID.WESTERN_DIARY_EASY_COMPLETE, VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE,
			VarbitID.WESTERN_DIARY_HARD_COMPLETE, VarbitID.WESTERN_DIARY_ELITE_COMPLETE},
		{VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE,
			VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE},
	};

	private final Client client;
	private final ClientThread clientThread;
	private final ItemManager itemManager;
	private final GptScapeConfig config;

	/** Itens do banco na última vez em que ele foi aberto nesta sessão; null se ainda não foi aberto. */
	private volatile Item[] bank;

	@Inject
	GameTools(Client client, ClientThread clientThread, ItemManager itemManager, GptScapeConfig config)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.itemManager = itemManager;
		this.config = config;
	}

	/** Guarda o conteúdo do banco, que o cliente só conhece enquanto ele está aberto. */
	void onBankChanged(ItemContainer container)
	{
		bank = container.getItems().clone();
	}

	/** Troca de conta: o banco guardado deixa de valer. */
	void forgetBank()
	{
		bank = null;
	}

	boolean isEnabled()
	{
		return config.shareAccountData() || config.shareBank();
	}

	boolean handles(String name)
	{
		return name.startsWith(PREFIX);
	}

	/** Declarações das ferramentas ativadas, no formato do Gemini; vazio se o usuário não compartilha nada. */
	JsonArray declarations()
	{
		JsonArray tools = new JsonArray();
		if (config.shareAccountData())
		{
			tools.add(tool("account_overview",
				"Reads the player's own account: account type (regular or ironman), quest points, combat and total "
					+ "level, every skill's level and XP, spellbook and unlocked prayers. Use it for questions about "
					+ "what the player can do or should train."));
			tools.add(tool("account_quests",
				"Lists the player's own quests: finished, in progress and not started. Use it for quest "
					+ "recommendations and to check quest requirements."));
			tools.add(tool("account_diaries",
				"Lists which Achievement Diary tiers the player has completed in each area."));
			tools.add(tool("account_gear",
				"Lists the items the player is wearing and carrying in the inventory right now. Use it for setup, "
					+ "gear and inventory advice."));
			tools.add(tool("account_slayer_task",
				"Reads the player's current Slayer task: monster, amount left and location."));
			tools.add(tool("account_ge_offers",
				"Lists the player's current Grand Exchange offers with item, quantity, price and progress."));
		}
		if (config.shareBank())
		{
			JsonObject bankTool = tool("account_bank",
				"Lists the items in the player's bank with quantities and Grand Exchange values, most valuable "
					+ "first. Use it for questions about what the player owns or can afford.");
			JsonObject search = new JsonObject();
			search.addProperty("type", "STRING");
			search.addProperty("description",
				"Optional. Part of an item name in English to look for (e.g. 'rune', 'potion'). Leave it out to "
					+ "list the most valuable items.");
			JsonObject properties = new JsonObject();
			properties.add("search", search);
			JsonObject parameters = new JsonObject();
			parameters.addProperty("type", "OBJECT");
			parameters.add("properties", properties);
			bankTool.add("parameters", parameters);
			tools.add(bankTool);
		}
		return tools;
	}

	private static JsonObject tool(String name, String description)
	{
		JsonObject function = new JsonObject();
		function.addProperty("name", name);
		function.addProperty("description", description);
		return function;
	}

	/** Texto curto para mostrar ao usuário enquanto a ferramenta roda. */
	static String describe(String name)
	{
		switch (name)
		{
			case "account_overview":
				return "Checking your account";
			case "account_quests":
				return "Checking your quests";
			case "account_diaries":
				return "Checking your diaries";
			case "account_gear":
				return "Checking your gear";
			case "account_slayer_task":
				return "Checking your Slayer task";
			case "account_ge_offers":
				return "Checking your GE offers";
			case "account_bank":
				return "Checking your bank";
			default:
				return "Checking your account";
		}
	}

	/** Executa a ferramenta (bloqueante, fora da thread do cliente) e devolve o resultado em texto. */
	String execute(String name, JsonObject args)
	{
		boolean bankTool = "account_bank".equals(name);
		// A configuração pode ter sido desligada depois que a ferramenta foi oferecida
		if (bankTool ? !config.shareBank() : !config.shareAccountData())
		{
			return "The player has not allowed sharing this information.";
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return NOT_LOGGED_IN;
		}

		Supplier<String> reader;
		switch (name)
		{
			case "account_overview":
				reader = this::overview;
				break;
			case "account_quests":
				reader = this::quests;
				break;
			case "account_diaries":
				reader = this::diaries;
				break;
			case "account_gear":
				reader = this::gear;
				break;
			case "account_slayer_task":
				reader = this::slayerTask;
				break;
			case "account_ge_offers":
				reader = this::geOffers;
				break;
			case "account_bank":
				String search = args.has("search") && args.get("search").isJsonPrimitive()
					? args.get("search").getAsString().trim() : "";
				reader = () -> bank(search);
				break;
			default:
				return "Unknown tool: " + name;
		}

		// A API do cliente deve ser lida na thread do cliente
		CompletableFuture<String> result = new CompletableFuture<>();
		clientThread.invoke(() ->
		{
			try
			{
				result.complete(reader.get());
			}
			catch (RuntimeException e)
			{
				result.completeExceptionally(e);
			}
		});

		try
		{
			return result.get(3, TimeUnit.SECONDS);
		}
		catch (Exception e)
		{
			log.debug("Could not read {} from the client", name, e);
			return "Could not read this information from the game client right now.";
		}
	}

	private String overview()
	{
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return NOT_LOGGED_IN;
		}

		StringBuilder sb = new StringBuilder()
			.append("Account type: ").append(pick(ACCOUNT_TYPES, client.getVarbitValue(VarbitID.IRONMAN))).append('\n')
			.append("World: ").append(client.getWorld())
			.append(client.getWorldType().contains(WorldType.MEMBERS) ? " (members)" : " (free-to-play)").append('\n')
			.append("Quest points: ").append(client.getVarpValue(VarPlayerID.QP)).append('\n')
			.append("Combat level: ").append(player.getCombatLevel()).append('\n')
			.append("Total level: ").append(client.getTotalLevel()).append('\n')
			.append("Spellbook: ").append(pick(SPELLBOOKS, client.getVarbitValue(VarbitID.SPELLBOOK))).append('\n')
			.append("Rigour unlocked: ").append(yesNo(VarbitID.PRAYER_RIGOUR_UNLOCKED)).append('\n')
			.append("Augury unlocked: ").append(yesNo(VarbitID.PRAYER_AUGURY_UNLOCKED)).append('\n')
			.append("Preserve unlocked: ").append(yesNo(VarbitID.PRAYER_PRESERVE_UNLOCKED)).append('\n')
			.append("\nSkills (level, current boosted level if different, XP):\n");
		for (Skill skill : Skill.values())
		{
			if ("OVERALL".equals(skill.name()))
			{
				continue;
			}
			int level = client.getRealSkillLevel(skill);
			int boosted = client.getBoostedSkillLevel(skill);
			sb.append("- ").append(skill.getName()).append(": ").append(level);
			if (boosted != level)
			{
				sb.append(" (currently ").append(boosted).append(')');
			}
			sb.append(", ").append(number(client.getSkillExperience(skill))).append(" XP\n");
		}
		return sb.toString();
	}

	private String quests()
	{
		List<String> finished = new ArrayList<>();
		List<String> inProgress = new ArrayList<>();
		List<String> notStarted = new ArrayList<>();
		for (Quest quest : Quest.values())
		{
			QuestState state = quest.getState(client);
			(state == QuestState.FINISHED ? finished : state == QuestState.IN_PROGRESS ? inProgress : notStarted)
				.add(quest.getName());
		}
		return "Quest points: " + client.getVarpValue(VarPlayerID.QP) + "\n\n"
			+ "In progress (" + inProgress.size() + "): " + join(inProgress) + "\n\n"
			+ "Not started (" + notStarted.size() + "): " + join(notStarted) + "\n\n"
			+ "Finished (" + finished.size() + "): " + join(finished) + '\n';
	}

	private String diaries()
	{
		StringBuilder sb = new StringBuilder("Achievement Diary tiers completed:\n");
		for (int area = 0; area < DIARY_AREAS.length; area++)
		{
			List<String> done = new ArrayList<>();
			for (int tier = 0; tier < DIARY_TIERS.length; tier++)
			{
				if (client.getVarbitValue(DIARY_VARBITS[area][tier]) == 1)
				{
					done.add(DIARY_TIERS[tier]);
				}
			}
			sb.append("- ").append(DIARY_AREAS[area]).append(": ").append(join(done)).append('\n');
		}
		return sb.toString();
	}

	private String gear()
	{
		StringBuilder sb = new StringBuilder("Worn equipment:\n");
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		int wornCount = 0;
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			Item item = worn != null ? worn.getItem(slot.getSlotIdx()) : null;
			if (item != null && item.getId() > 0 && item.getQuantity() > 0)
			{
				sb.append("- ").append(slotName(slot)).append(": ").append(describe(item)).append('\n');
				wornCount++;
			}
		}
		if (wornCount == 0)
		{
			sb.append("- nothing\n");
		}

		sb.append("\nInventory:\n");
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		int carried = 0;
		if (inventory != null)
		{
			for (Item item : inventory.getItems())
			{
				if (item.getId() > 0 && item.getQuantity() > 0)
				{
					sb.append("- ").append(describe(item)).append('\n');
					carried++;
				}
			}
		}
		if (carried == 0)
		{
			sb.append("- empty\n");
		}
		return sb.toString();
	}

	private String slayerTask()
	{
		int amount = client.getVarpValue(VarPlayerID.SLAYER_COUNT);
		if (amount <= 0)
		{
			return "The player has no Slayer task right now.";
		}

		int taskId = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
		int taskRow;
		if (taskId == SLAYER_BOSS_TASK)
		{
			List<Integer> bossRows = client.getDBRowsByValue(DBTableID.SlayerTaskSublist.ID,
				DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID, 0, client.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID));
			if (bossRows.isEmpty())
			{
				return "The player has a boss Slayer task with " + amount + " left, but its name is not available.";
			}
			taskRow = (Integer) client.getDBTableField(bossRows.get(0), DBTableID.SlayerTaskSublist.COL_TASK, 0)[0];
		}
		else
		{
			List<Integer> taskRows = client.getDBRowsByValue(DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, 0,
				taskId);
			if (taskRows.isEmpty())
			{
				return "The player has a Slayer task with " + amount + " left, but its name is not available.";
			}
			taskRow = taskRows.get(0);
		}

		StringBuilder sb = new StringBuilder("Slayer task: ")
			.append(client.getDBTableField(taskRow, DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0)[0]).append('\n')
			.append("Left to kill: ").append(amount).append('\n');
		int assigned = client.getVarpValue(VarPlayerID.SLAYER_COUNT_ORIGINAL);
		if (assigned > 0)
		{
			sb.append("Assigned: ").append(assigned).append('\n');
		}
		int areaId = client.getVarpValue(VarPlayerID.SLAYER_AREA);
		if (areaId > 0)
		{
			List<Integer> areaRows = client.getDBRowsByValue(DBTableID.SlayerArea.ID, DBTableID.SlayerArea.COL_AREA_ID, 0,
				areaId);
			if (!areaRows.isEmpty())
			{
				sb.append("Location: ")
					.append(client.getDBTableField(areaRows.get(0), DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER, 0)[0])
					.append('\n');
			}
		}
		return sb.toString();
	}

	private String geOffers()
	{
		StringBuilder sb = new StringBuilder();
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers != null)
		{
			for (GrandExchangeOffer offer : offers)
			{
				if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY || offer.getItemId() <= 0)
				{
					continue;
				}
				sb.append("- ").append(offerState(offer.getState())).append(": ")
					.append(itemManager.getItemComposition(offer.getItemId()).getName())
					.append(", ").append(number(offer.getQuantitySold())).append(" of ")
					.append(number(offer.getTotalQuantity())).append(" done, ")
					.append(number(offer.getPrice())).append(" gp each\n");
			}
		}
		return sb.length() == 0 ? "The player has no Grand Exchange offers right now."
			: "Grand Exchange offers:\n" + sb;
	}

	private String bank(String search)
	{
		Item[] items = bank;
		if (items == null)
		{
			return "The bank contents are not known yet: the player has not opened their bank since starting "
				+ "RuneLite. Ask them to open the bank once and then ask again.";
		}

		String filter = search.toLowerCase(Locale.ROOT);
		List<BankEntry> entries = new ArrayList<>();
		long total = 0;
		for (Item item : items)
		{
			if (item.getId() <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			ItemComposition composition = itemManager.getItemComposition(item.getId());
			// Placeholders ocupam um espaço no banco, mas não são itens de verdade
			if (composition.getPlaceholderTemplateId() != -1)
			{
				continue;
			}
			long value = (long) itemManager.getItemPrice(item.getId()) * item.getQuantity();
			total += value;
			if (filter.isEmpty() || composition.getName().toLowerCase(Locale.ROOT).contains(filter))
			{
				entries.add(new BankEntry(composition.getName(), item.getQuantity(), value));
			}
		}
		entries.sort(Comparator.comparingLong((BankEntry e) -> e.value).reversed());

		StringBuilder sb = new StringBuilder("Bank as of the last time it was opened. Total value: about ")
			.append(number(total)).append(" gp.\n");
		if (entries.isEmpty())
		{
			return sb.append(filter.isEmpty() ? "The bank is empty." : "No items match '" + search + "'.").toString();
		}
		sb.append(filter.isEmpty() ? "Items" : "Items matching '" + search + "'")
			.append(" (name, quantity, total value), most valuable first:\n");
		int shown = Math.min(entries.size(), MAX_BANK_ITEMS);
		for (BankEntry entry : entries.subList(0, shown))
		{
			sb.append("- ").append(entry.name).append(" x").append(number(entry.quantity))
				.append(" (").append(number(entry.value)).append(" gp)\n");
		}
		if (shown < entries.size())
		{
			sb.append("...and ").append(entries.size() - shown)
				.append(" less valuable items. Use the search parameter to look for a specific one.\n");
		}
		return sb.toString();
	}

	private static final class BankEntry
	{
		private final String name;
		private final int quantity;
		private final long value;

		private BankEntry(String name, int quantity, long value)
		{
			this.name = name;
			this.quantity = quantity;
			this.value = value;
		}
	}

	private String describe(Item item)
	{
		String name = itemManager.getItemComposition(item.getId()).getName();
		return item.getQuantity() > 1 ? name + " x" + number(item.getQuantity()) : name;
	}

	private String yesNo(int varbit)
	{
		return client.getVarbitValue(varbit) == 1 ? "yes" : "no";
	}

	private static String pick(String[] names, int index)
	{
		return index >= 0 && index < names.length ? names[index] : "Unknown";
	}

	private static String join(List<String> names)
	{
		return names.isEmpty() ? "none" : String.join(", ", names);
	}

	private static String number(long value)
	{
		return String.format(Locale.US, "%,d", value);
	}

	private static String slotName(EquipmentInventorySlot slot)
	{
		String name = slot.name().toLowerCase(Locale.ROOT);
		return Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

	private static String offerState(GrandExchangeOfferState state)
	{
		switch (state)
		{
			case BUYING:
				return "Buying";
			case BOUGHT:
				return "Bought";
			case CANCELLED_BUY:
				return "Buy cancelled";
			case SELLING:
				return "Selling";
			case SOLD:
				return "Sold";
			case CANCELLED_SELL:
				return "Sell cancelled";
			default:
				return "Offer";
		}
	}
}
