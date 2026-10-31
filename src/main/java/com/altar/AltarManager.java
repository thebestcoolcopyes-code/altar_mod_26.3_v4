package com.altar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Prediction;
import net.minecraft.world.BossEvent;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** All altar state, persistence, ritual logic and hologram handling. One instance per running server. */
public final class AltarManager {
	public static AltarManager INSTANCE;

	public static final String TAG_ITEM = "altar_item_";
	public static final String TAG_TEXT = "altar_text_";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Global toggles with their defaults. */
	public static final Map<String, Boolean> TOGGLE_DEFAULTS = new LinkedHashMap<>();
	public static final Map<String, String> TOGGLE_DESCRIPTIONS = new LinkedHashMap<>();
	static {
		TOGGLE_DEFAULTS.put("enabled", true);    // altars can be used at all
		TOGGLE_DESCRIPTIONS.put("enabled", "Allow players to start altar rituals.");
		TOGGLE_DEFAULTS.put("protect", true);    // altar blocks cannot be broken
		TOGGLE_DESCRIPTIONS.put("protect", "Stop players from breaking altar blocks.");
		TOGGLE_DEFAULTS.put("oneuse", true);     // default for NEW altars: one-time use (changeable per altar in the GUI)
		TOGGLE_DESCRIPTIONS.put("oneuse", "Make new altars disappear after one successful ritual.");
		TOGGLE_DEFAULTS.put("cancel", true);     // ritual is cancelled if the starter dies / disconnects
		TOGGLE_DESCRIPTIONS.put("cancel", "Cancel a ritual when its starter dies or disconnects.");
		TOGGLE_DEFAULTS.put("broadcast", true);  // chat announcements
		TOGGLE_DESCRIPTIONS.put("broadcast", "Announce ritual starts, finishes, and cancellations in chat.");
		TOGGLE_DEFAULTS.put("bossbar", true);    // boss bar for everyone while a ritual runs
		TOGGLE_DESCRIPTIONS.put("bossbar", "Show everyone a progress bar during active rituals.");
		TOGGLE_DEFAULTS.put("particles", true);  // fire / smoke while a ritual runs
		TOGGLE_DESCRIPTIONS.put("particles", "Show flame and smoke particles during rituals.");
		TOGGLE_DEFAULTS.put("hologram", true);   // floating item + text above the altar
		TOGGLE_DESCRIPTIONS.put("hologram", "Show the forged item and recipe above each altar.");
		TOGGLE_DEFAULTS.put("announcecreate", true);
		TOGGLE_DESCRIPTIONS.put("announcecreate", "Announce newly created altars in chat.");
	}

	public final MinecraftServer server;
	public final Map<String, Altar> altars = new LinkedHashMap<>();
	public final Map<String, Boolean> toggles = new LinkedHashMap<>(TOGGLE_DEFAULTS);
	public boolean stopping = false;
	private int nextId = 1;
	private long tickCounter = 0;
	/** Actions deferred to the next server tick (used to switch menus safely). */
	private final List<Runnable> later = new ArrayList<>();

	public AltarManager(MinecraftServer server) {
		this.server = server;
	}

	// ------------------------------------------------------------------ helpers

	public static String key(ResourceKey<Level> dim, BlockPos pos) {
		return dim.identifier() + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	public Altar at(ResourceKey<Level> dim, BlockPos pos) {
		return altars.get(key(dim, pos));
	}

	public boolean toggle(String name) {
		return toggles.getOrDefault(name, TOGGLE_DEFAULTS.getOrDefault(name, false));
	}

	public static String worldName(ResourceKey<Level> k) {
		if (k == Level.OVERWORLD) return "world";
		if (k == Level.NETHER) return "world_nether";
		if (k == Level.END) return "world_the_end";
		return k.identifier().toString();
	}

	public static String where(Altar a) {
		return worldName(a.dim) + " " + a.pos.getX() + " " + a.pos.getY() + " " + a.pos.getZ();
	}

	public static MutableComponent prefix() {
		return Component.literal("ALTAR").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
				.append(Component.literal(" ➜ ").withStyle(ChatFormatting.GRAY));
	}

	private void broadcast(Component t) {
		server.getPlayerList().broadcastSystemMessage(t, false);
	}

	private static ItemStack normalize(ItemStack s) {
		ItemStack c = s.copy();
		if (c.is(Items.PLAYER_HEAD)) c.remove(DataComponents.PROFILE);
		return c;
	}

	/** Exact-ish equality used when merging recipe entries. */
	private static boolean same(ItemStack a, ItemStack b) {
		return a.is(b.getItem())
				&& Objects.equals(a.get(DataComponents.CUSTOM_NAME), b.get(DataComponents.CUSTOM_NAME))
				&& Objects.equals(a.get(DataComponents.CUSTOM_DATA), b.get(DataComponents.CUSTOM_DATA));
	}

	/** Does the player's stack satisfy the requirement? Item type must match; name / custom data only if required. */
	private static boolean matches(ItemStack have, ItemStack need) {
		if (have.isEmpty() || !have.is(need.getItem())) return false;
		if (need.get(DataComponents.CUSTOM_NAME) != null
				&& !Objects.equals(have.get(DataComponents.CUSTOM_NAME), need.get(DataComponents.CUSTOM_NAME)))
			return false;
		if (need.get(DataComponents.CUSTOM_DATA) != null
				&& !Objects.equals(have.get(DataComponents.CUSTOM_DATA), need.get(DataComponents.CUSTOM_DATA)))
			return false;
		return true;
	}

	private static boolean isInvSlot(int i) {
		return i < 36 || i == 40; // main + hotbar + offhand, not armour
	}

	private static int countIn(Inventory inv, ItemStack need) {
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (!isInvSlot(i)) continue;
			ItemStack s = inv.getItem(i);
			if (matches(s, need)) n += s.getCount();
		}
		return n;
	}

	private static void take(Inventory inv, ItemStack need) {
		int left = need.getCount();
		for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
			if (!isInvSlot(i)) continue;
			ItemStack s = inv.getItem(i);
			if (!matches(s, need)) continue;
			int t = Math.min(left, s.getCount());
			s.shrink(t);
			left -= t;
		}
	}

	private static void dropSplit(ServerLevel w, BlockPos pos, ItemStack stack) {
		int left = stack.getCount();
		int max = Math.max(1, stack.getMaxStackSize());
		while (left > 0) {
			int n = Math.min(left, max);
			Block.popResource(w, pos, stack.copyWithCount(n));
			left -= n;
		}
	}

	// ------------------------------------------------------------------ persistence

	private Path file() {
		return server.getWorldPath(LevelResource.ROOT).resolve("altar_mod.json");
	}

	private RegistryOps<JsonElement> ops() {
		return server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
	}

	private JsonElement enc(ItemStack s) {
		return ItemStack.CODEC.encodeStart(ops(), s).result().orElse(null);
	}

	private ItemStack dec(JsonElement e) {
		return ItemStack.CODEC.parse(ops(), e).result().orElse(ItemStack.EMPTY);
	}

	public void save() {
		try {
			JsonObject root = new JsonObject();
			JsonObject t = new JsonObject();
			for (Map.Entry<String, Boolean> e : toggles.entrySet()) t.addProperty(e.getKey(), e.getValue());
			root.add("toggles", t);
			root.addProperty("nextId", nextId);
			JsonArray arr = new JsonArray();
			for (Altar a : altars.values()) {
				JsonObject o = new JsonObject();
				o.addProperty("id", a.id);
				o.addProperty("dim", a.dim.identifier().toString());
				o.addProperty("x", a.pos.getX());
				o.addProperty("y", a.pos.getY());
				o.addProperty("z", a.pos.getZ());
				o.addProperty("seconds", a.seconds);
				o.addProperty("oneUse", a.oneUse);
				o.addProperty("restartOnDeath", a.restartOnDeath);
				JsonElement r = enc(a.result);
				if (r != null) o.add("result", r);
				JsonArray rec = new JsonArray();
				for (ItemStack s : a.recipe) {
					JsonElement je = enc(s);
					if (je != null) rec.add(je);
				}
				o.add("recipe", rec);
				if (a.active && a.starter != null) {
					o.addProperty("starter", a.starter.toString());
					o.addProperty("starterName", a.starterName);
					o.addProperty("ticksLeft", a.ticksLeft);
					o.addProperty("totalTicks", a.totalTicks);
				}
				arr.add(o);
			}
			root.add("altars", arr);
			Files.writeString(file(), GSON.toJson(root), StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException ex) {
			AltarMod.LOGGER.error("Failed to save altars", ex);
		}
	}

	public void load() {
		Path f = file();
		if (!Files.exists(f)) return;
		try {
			JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
			if (root.has("toggles")) {
				for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("toggles").entrySet()) {
					if (TOGGLE_DEFAULTS.containsKey(e.getKey())) toggles.put(e.getKey(), e.getValue().getAsBoolean());
				}
			}
			if (root.has("nextId")) nextId = root.get("nextId").getAsInt();
			if (root.has("altars")) {
				for (JsonElement el : root.getAsJsonArray("altars")) {
					JsonObject o = el.getAsJsonObject();
					ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(o.get("dim").getAsString()));
					BlockPos pos = new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
					Altar a = new Altar(o.get("id").getAsInt(), dim, pos);
					a.seconds = o.get("seconds").getAsInt();
					a.oneUse = o.has("oneUse") ? o.get("oneUse").getAsBoolean() : toggle("oneuse");
					a.restartOnDeath = o.has("restartOnDeath") && o.get("restartOnDeath").getAsBoolean();
					if (o.has("result")) a.result = dec(o.get("result"));
					if (o.has("recipe")) {
						for (JsonElement r : o.getAsJsonArray("recipe")) {
							ItemStack s = dec(r);
							if (!s.isEmpty()) a.recipe.add(s);
						}
					}
					if (o.has("starter")) {
						a.active = true;
						a.starter = UUID.fromString(o.get("starter").getAsString());
						a.starterName = o.has("starterName") ? o.get("starterName").getAsString() : "";
						a.ticksLeft = o.get("ticksLeft").getAsInt();
						a.totalTicks = o.has("totalTicks") ? o.get("totalTicks").getAsInt() : a.seconds * 20;
					}
					nextId = Math.max(nextId, a.id + 1);
					altars.put(key(dim, pos), a);
				}
			}
			AltarMod.LOGGER.info("Loaded {} altar(s)", altars.size());
		} catch (IOException | RuntimeException ex) {
			AltarMod.LOGGER.error("Failed to load altars", ex);
		}
	}

	// ------------------------------------------------------------------ setting up altars

	private BlockPos lookedAt(ServerPlayer p) {
		HitResult hit = p.pick(6.0, 1.0f, false);
		return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK ? block.getBlockPos() : null;
	}

	private Altar lookedAtAltar(ServerPlayer p, String action) {
		BlockPos pos = lookedAt(p);
		Altar altar = pos == null ? null : at(((ServerLevel) p.level()).dimension(), pos);
		if (altar == null) p.sendSystemMessage(Component.literal("Look at an altar within 6 blocks to " + action + ".").withStyle(ChatFormatting.RED));
		return altar;
	}

	private static ItemStack menuIcon(net.minecraft.world.item.Item item, String title, ChatFormatting color, String... lore) {
		List<Component> lines = new ArrayList<>();
		for (String text : lore) lines.add(line(text, ChatFormatting.GRAY));
		return icon(item, line(title, color), lines.toArray(Component[]::new));
	}

	private ItemStack toggleIcon(String name) {
		boolean value = toggle(name);
		String label = name.equals("announcecreate") ? "Broadcast new altars" : name;
		return menuIcon(value ? Items.EMERALD : Items.COAL,
				label + ": " + (value ? "ON" : "OFF"), value ? ChatFormatting.GREEN : ChatFormatting.RED,
				TOGGLE_DESCRIPTIONS.getOrDefault(name, "Click to change this setting."), "Click to toggle.");
	}

	/** Open the combined admin command and settings screen. */
	public void openDashboard(ServerPlayer p) {
		SimpleContainer inv = new SimpleContainer(54);
		inv.setItem(10, menuIcon(Items.ANVIL, "Create altar", ChatFormatting.GOLD,
				"Choose a ritual length and announcement option.", "Look at a block and hold the forged item."));
		inv.setItem(12, menuIcon(Items.CRAFTING_TABLE, "Edit recipe", ChatFormatting.AQUA,
				"Edit the sacrifice recipe for the altar you are looking at."));
		inv.setItem(14, menuIcon(Items.COMPARATOR, "Altar settings", ChatFormatting.YELLOW,
				"Change one-use and starter-death settings for the altar you are looking at."));
		inv.setItem(16, menuIcon(Items.BARRIER, "Remove altar", ChatFormatting.RED,
				"Remove the altar you are looking at."));
		inv.setItem(22, menuIcon(Items.TNT, "Remove all altars", ChatFormatting.RED,
				"Open a confirmation before removing every altar."));
		String[] names = TOGGLE_DEFAULTS.keySet().toArray(String[]::new);
		for (int i = 0; i < names.length; i++) inv.setItem(27 + i, toggleIcon(names[i]));
		inv.setItem(49, menuIcon(Items.BOOK, "Admin controls", ChatFormatting.LIGHT_PURPLE,
				"Choose an action above or click a setting below.", "All changes save automatically."));
		p.openMenu(new SimpleMenuProvider((id, playerInv, player) -> new ChestMenu(MenuType.GENERIC_9x6, id, playerInv, inv, 6) {
			@Override public void clicked(int slot, int button, ContainerInput click, Player who) {
				if (slot >= 0 && slot < 54) { if (who instanceof ServerPlayer sp) dashboardClick(sp, slot); this.broadcastChanges(); return; }
			}
			@Override public ItemStack quickMoveStack(Player who, int index) { return ItemStack.EMPTY; }
		}, Component.literal("Altar Admin").withStyle(ChatFormatting.RED)));
	}

	private void dashboardClick(ServerPlayer p, int slot) {
		if (slot >= 27 && slot < 27 + TOGGLE_DEFAULTS.size()) {
			String name = new ArrayList<>(TOGGLE_DEFAULTS.keySet()).get(slot - 27);
			toggles.put(name, !toggle(name)); save(); later.add(() -> openDashboard(p)); return;
		}
		switch (slot) {
			case 10 -> later.add(() -> openCreateWizard(p));
			case 12 -> { Altar a = lookedAtAltar(p, "edit its recipe"); if (a != null) later.add(() -> openEditor(p, a)); }
			case 14 -> { Altar a = lookedAtAltar(p, "change its settings"); if (a != null) later.add(() -> openSettings(p, a)); }
			case 16 -> { Altar a = lookedAtAltar(p, "remove it"); if (a != null) { removeAltar(a, true); p.sendSystemMessage(line("Altar removed.", ChatFormatting.GREEN)); } }
			case 22 -> later.add(() -> openClearAllConfirm(p));
		}
	}

	private void openCreateWizard(ServerPlayer p) {
		SimpleContainer inv = new SimpleContainer(27);
		int[] seconds = {900};
		Runnable[] refresh = new Runnable[1];
		refresh[0] = () -> {
			for (int i = 0; i < 27; i++) inv.setItem(i, ItemStack.EMPTY);
			inv.setItem(10, menuIcon(Items.TURTLE_EGG, "- 60 seconds", ChatFormatting.YELLOW, "Decrease ritual length by one minute."));
			inv.setItem(11, menuIcon(Items.REDSTONE, "- 5 seconds", ChatFormatting.YELLOW, "Decrease ritual length by five seconds."));
			inv.setItem(13, menuIcon(Items.CLOCK, "Ritual length: " + seconds[0] + "s", ChatFormatting.GOLD, "Choose from 1 second to 24 hours."));
			inv.setItem(15, menuIcon(Items.GLOWSTONE_DUST, "+ 5 seconds", ChatFormatting.YELLOW, "Increase ritual length by five seconds."));
			inv.setItem(16, menuIcon(Items.GOLD_INGOT, "+ 60 seconds", ChatFormatting.YELLOW, "Increase ritual length by one minute."));
			inv.setItem(22, toggleIcon("announcecreate"));
			inv.setItem(18, menuIcon(Items.ARROW, "Back", ChatFormatting.GRAY, "Return to the admin screen."));
			inv.setItem(26, menuIcon(Items.ANVIL, "Create altar", ChatFormatting.GREEN,
					"Look at the block to use and hold the forged item in your main hand."));
		};
		refresh[0].run();
		p.openMenu(new SimpleMenuProvider((id, playerInv, player) -> new ChestMenu(MenuType.GENERIC_9x3, id, playerInv, inv, 3) {
			@Override public void clicked(int slot, int button, ContainerInput click, Player who) {
				if (slot < 0 || slot >= 27 || !(who instanceof ServerPlayer sp)) return;
				switch (slot) {
					case 10 -> seconds[0] = Math.max(1, seconds[0] - 60);
					case 11 -> seconds[0] = Math.max(1, seconds[0] - 5);
					case 15 -> seconds[0] = Math.min(86400, seconds[0] + 5);
					case 16 -> seconds[0] = Math.min(86400, seconds[0] + 60);
					case 22 -> { toggles.put("announcecreate", !toggle("announcecreate")); save(); }
					case 18 -> { later.add(() -> openDashboard(sp)); return; }
					case 26 -> {
						BlockPos pos = lookedAt(sp); ItemStack held = sp.getMainHandItem();
						if (pos == null) { sp.sendSystemMessage(line("Look at the block you want to set as the altar (within 6 blocks).", ChatFormatting.RED)); return; }
						if (held.isEmpty()) { sp.sendSystemMessage(line("Hold the item you want this altar to forge in your main hand.", ChatFormatting.RED)); return; }
						int ritualSeconds = seconds[0];
						later.add(() -> setAltar(sp, pos, held.copy(), ritualSeconds)); return;
					}
				}
				refresh[0].run(); this.broadcastChanges();
			}
			@Override public ItemStack quickMoveStack(Player who, int index) { return ItemStack.EMPTY; }
		}, Component.literal("Create Altar").withStyle(ChatFormatting.RED)));
	}

	private void openClearAllConfirm(ServerPlayer p) {
		SimpleContainer inv = new SimpleContainer(27);
		inv.setItem(11, menuIcon(Items.BARRIER, "Keep altars", ChatFormatting.GREEN, "Return to the admin screen."));
		inv.setItem(15, menuIcon(Items.TNT, "Remove all " + altars.size() + " altars", ChatFormatting.RED, "This action cannot be undone."));
		p.openMenu(new SimpleMenuProvider((id, playerInv, player) -> new ChestMenu(MenuType.GENERIC_9x3, id, playerInv, inv, 3) {
			@Override public void clicked(int slot, int button, ContainerInput click, Player who) {
				if (!(who instanceof ServerPlayer sp)) return;
				if (slot == 11) later.add(() -> openDashboard(sp));
				else if (slot == 15) later.add(() -> { int count = clearAll(); sp.sendSystemMessage(line("Cleared " + count + " altar(s).", ChatFormatting.GREEN)); sp.closeContainer(); });
			}
			@Override public ItemStack quickMoveStack(Player who, int index) { return ItemStack.EMPTY; }
		}, Component.literal("Confirm Clear All").withStyle(ChatFormatting.RED)));
	}

	/** Create (or re-configure) an altar and open the recipe editor. Returns false if a ritual blocks it. */
	public boolean setAltar(ServerPlayer p, BlockPos pos, ItemStack held, int seconds) {
		ServerLevel w = (ServerLevel) p.level();
		Altar a = at(w.dimension(), pos);
		boolean isNew = a == null;
		if (a != null && a.active) {
			p.sendSystemMessage(Component.literal("A ritual is in progress on that altar.").withStyle(ChatFormatting.RED), false);
			return false;
		}
		if (a == null) {
			a = new Altar(nextId++, w.dimension(), pos);
			a.oneUse = toggle("oneuse");
			altars.put(key(a.dim, a.pos), a);
		}
		a.result = held.copy();
		a.seconds = seconds;
		refreshHologram(a);
		save();
		p.sendSystemMessage(Component.literal("Altar set at ").withStyle(ChatFormatting.GREEN)
				.append(Component.literal(where(a)).withStyle(ChatFormatting.YELLOW))
				.append(Component.literal(" (settings opened)").withStyle(ChatFormatting.GREEN)), false);
		if (isNew && toggle("announcecreate")) broadcast(prefix().append(Component.literal(p.getName().getString() + " created an altar at " + where(a) + ".").withStyle(ChatFormatting.YELLOW)));
		openSettings(p, a);
		return true;
	}

	public void openEditor(ServerPlayer p, Altar altar) {
		if (altar.active) {
			p.sendSystemMessage(Component.literal("A ritual is in progress on that altar.").withStyle(ChatFormatting.RED), false);
			return;
		}
		SimpleContainer inv = new SimpleContainer(27);
		int slot = 0;
		for (ItemStack s : altar.recipe) {
			int left = s.getCount();
			int max = Math.max(1, s.getMaxStackSize());
			while (left > 0 && slot < 27) {
				int n = Math.min(left, max);
				inv.setItem(slot++, s.copyWithCount(n));
				left -= n;
			}
		}
		final String k = key(altar.dim, altar.pos);
		p.openMenu(new SimpleMenuProvider((containerId, playerInv, player) ->
				new ChestMenu(MenuType.GENERIC_9x3, containerId, playerInv, inv, 3) {
					@Override
					public void removed(Player pl) {
						super.removed(pl);
						saveEditor(k, inv, pl);
					}
				}, Component.literal("Altar Recipe").withStyle(ChatFormatting.RED)));
	}

	private void saveEditor(String k, SimpleContainer inv, Player pl) {
		Altar a = altars.get(k);
		List<ItemStack> merged = new ArrayList<>();
		boolean heads = false;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) continue;
			if (s.is(Items.PLAYER_HEAD)) heads = true;
			ItemStack n = normalize(s);
			boolean found = false;
			for (ItemStack m : merged) {
				if (same(m, n)) {
					m.setCount(m.getCount() + n.getCount());
					found = true;
					break;
				}
			}
			if (!found) merged.add(n);
		}
		// The editor is a ghost recipe: hand the real items back.
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty()) pl.getInventory().placeItemBackInInventory(s, Prediction.SERVER_ONLY);
			inv.setItem(i, ItemStack.EMPTY);
		}
		if (a == null) return; // altar was cleared while the editor was open
		a.recipe = merged;
		refreshHologram(a);
		save();
pl.sendSystemMessage(Component.literal("Recipe saved." + (heads ? " Player heads were normalised." : "")).withStyle(ChatFormatting.GREEN));
	}


	// ------------------------------------------------------------------ settings GUI

	private static final int SLOT_RESULT = 4, SLOT_MODE = 11, SLOT_RECIPE = 13, SLOT_DEATH = 15;

	private static ItemStack icon(net.minecraft.world.item.Item item, Component name, Component... lore) {
		ItemStack s = new ItemStack(item);
		s.set(DataComponents.ITEM_NAME, name);
		if (lore.length > 0) s.set(DataComponents.LORE, new ItemLore(List.of(lore)));
		return s;
	}

	private static Component line(String text, ChatFormatting color) {
		return Component.literal(text).withStyle(color);
	}

	private void fillSettings(SimpleContainer inv, Altar a) {
		for (int i = 0; i < inv.getContainerSize(); i++) inv.setItem(i, ItemStack.EMPTY);

		ItemStack result = a.result.isEmpty() ? icon(Items.BARRIER, line("No item set", ChatFormatting.RED)) : a.result.copy();
		inv.setItem(SLOT_RESULT, result);

		if (a.oneUse) {
			inv.setItem(SLOT_MODE, icon(Items.FIRE_CHARGE, line("Altar type: ONE-TIME USE", ChatFormatting.GOLD),
					line("The altar disappears after its ritual finishes.", ChatFormatting.GRAY),
					line("Click to make it PERMANENT.", ChatFormatting.YELLOW)));
		} else {
			inv.setItem(SLOT_MODE, icon(Items.BEACON, line("Altar type: PERMANENT", ChatFormatting.GREEN),
					line("The altar stays and can be used again.", ChatFormatting.GRAY),
					line("Click to make it ONE-TIME USE.", ChatFormatting.YELLOW)));
		}

		inv.setItem(SLOT_RECIPE, icon(Items.CRAFTING_TABLE, line("Edit recipe", ChatFormatting.AQUA),
				line("Items players must sacrifice.", ChatFormatting.GRAY),
				line("Ritual length: " + a.seconds + "s", ChatFormatting.GRAY),
				line("Click to open the recipe editor.", ChatFormatting.YELLOW)));

		if (a.restartOnDeath) {
			inv.setItem(SLOT_DEATH, icon(Items.CLOCK, line("If the starter is killed: RESTART RITUAL", ChatFormatting.AQUA),
					line("The ritual resets, the sacrificed items drop", ChatFormatting.GRAY),
					line("at the altar and it can be started again.", ChatFormatting.GRAY),
					line("Click to switch to DISAPPEAR.", ChatFormatting.YELLOW)));
		} else {
			inv.setItem(SLOT_DEATH, icon(Items.SKELETON_SKULL, line("If the starter is killed: DISAPPEAR", ChatFormatting.RED),
					line("The altar is removed (items drop at the altar).", ChatFormatting.GRAY),
					line("Click to switch to RESTART RITUAL.", ChatFormatting.YELLOW)));
		}
	}

	public void openSettings(ServerPlayer p, Altar altar) {
		final String k = key(altar.dim, altar.pos);
		SimpleContainer inv = new SimpleContainer(27);
		fillSettings(inv, altar);
		p.openMenu(new SimpleMenuProvider((containerId, playerInv, player) ->
				new ChestMenu(MenuType.GENERIC_9x3, containerId, playerInv, inv, 3) {
					@Override
					public void clicked(int slotId, int button, ContainerInput clickType, Player pl) {
						if (slotId >= 0 && slotId < 27) {
							onSettingsClick(k, slotId, inv, pl);
							this.broadcastChanges();
							return;
						}
						// only plain clicks in the player's own inventory; no dragging / double-click / shift tricks
						if (clickType == ContainerInput.PICKUP) super.clicked(slotId, button, clickType, pl);
					}

					@Override
					public ItemStack quickMoveStack(Player pl, int index) {
						return ItemStack.EMPTY;
					}
				}, Component.literal("Altar Settings").withStyle(ChatFormatting.RED)));
	}

	private void onSettingsClick(String k, int slot, SimpleContainer inv, Player pl) {
		Altar a = altars.get(k);
		if (a == null) {
			if (pl instanceof ServerPlayer sp) later.add(sp::closeContainer);
			return;
		}
		if (slot != SLOT_MODE && slot != SLOT_RECIPE && slot != SLOT_DEATH) return;
		if (a.active) {
			pl.sendSystemMessage(Component.literal("A ritual is in progress on that altar.").withStyle(ChatFormatting.RED));
			return;
		}
		if (slot == SLOT_MODE) {
			a.oneUse = !a.oneUse;
			save();
			fillSettings(inv, a);
		} else if (slot == SLOT_DEATH) {
			a.restartOnDeath = !a.restartOnDeath;
			save();
			fillSettings(inv, a);
		} else if (pl instanceof ServerPlayer sp) {
			// switch to the recipe editor on the next tick (never swap menus from inside a click handler)
			later.add(() -> {
				sp.closeContainer();
				Altar cur = altars.get(k);
				if (cur != null) openEditor(sp, cur);
			});
		}
	}

	/** Starter died / left and the altar is set to restart: refund the sacrifice and go back to idle. */
	public void resetRitual(Altar a) {
		ServerLevel w = server.getLevel(a.dim);
		if (w != null) {
			for (ItemStack s : a.recipe) dropSplit(w, a.pos.above(), s);
		}
		hideBar(a);
		a.active = false;
		a.starter = null;
		a.ticksLeft = 0;
		if (toggle("broadcast")) {
			broadcast(prefix().append(Component.literal(a.starterName + " was eliminated. The ritual at " + where(a) + " has been reset.")
					.withStyle(ChatFormatting.RED)));
		}
		refreshHologram(a);
		save();
	}

	// ------------------------------------------------------------------ rituals

	public void tryStart(ServerPlayer p, Altar a) {
		if (!toggle("enabled")) {
			p.sendSystemMessage(Component.literal("Altars are currently disabled.").withStyle(ChatFormatting.RED), false);
			return;
		}
		if (a.result.isEmpty()) {
			p.sendSystemMessage(Component.literal("This altar has no item to forge. Use /altar set.").withStyle(ChatFormatting.RED), false);
			return;
		}
		if (a.active) {
			p.sendSystemMessage(Component.literal("A ritual is already in progress here (" + (a.ticksLeft / 20 + 1) + "s left).")
					.withStyle(ChatFormatting.RED), false);
			return;
		}
		Inventory inv = p.getInventory();
		List<String> missing = new ArrayList<>();
		for (ItemStack need : a.recipe) {
			int have = countIn(inv, need);
			if (have < need.getCount()) missing.add((need.getCount() - have) + "x " + need.getHoverName().getString());
		}
		if (!missing.isEmpty()) {
			p.sendSystemMessage(Component.literal("You are missing: " + String.join(", ", missing)).withStyle(ChatFormatting.RED), false);
			return;
		}
		for (ItemStack need : a.recipe) take(inv, need);
		inv.setChanged();
		p.inventoryMenu.broadcastChanges();

		a.active = true;
		a.starter = p.getUUID();
		a.starterName = p.getName().getString();
		a.totalTicks = a.seconds * 20;
		a.ticksLeft = a.totalTicks;

		ServerLevel w = server.getLevel(a.dim);
		if (w != null) {
			w.playSound(null, a.pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.0f, 1.0f);
		}
		if (toggle("broadcast")) {
			broadcast(prefix()
					.append(Component.literal(a.starterName).withStyle(ChatFormatting.YELLOW))
					.append(Component.literal(" has begun forging ").withStyle(ChatFormatting.WHITE))
					.append(a.result.getHoverName().copy().withStyle(ChatFormatting.RED))
					.append(Component.literal(" at ").withStyle(ChatFormatting.WHITE))
					.append(Component.literal(where(a)).withStyle(ChatFormatting.YELLOW))
					.append(Component.literal(" (" + a.seconds + "s)").withStyle(ChatFormatting.GRAY)));
		}
		refreshHologram(a);
		save();
	}

	private void complete(ServerLevel w, Altar a) {
		removeDisplays(w, a);
		BlockPos above = a.pos.above();
		dropSplit(w, above, a.result);
		w.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, a.pos.getX() + 0.5, a.pos.getY() + 1.2, a.pos.getZ() + 0.5,
				50, 0.4, 0.5, 0.4, 0.3);
		w.playSound(null, a.pos, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 1.0f, 1.0f);
		w.playSound(null, a.pos, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 1.0f, 1.0f);
		if (toggle("broadcast")) {
			broadcast(prefix()
					.append(Component.literal(a.starterName).withStyle(ChatFormatting.YELLOW))
					.append(Component.literal(" has forged ").withStyle(ChatFormatting.WHITE))
					.append(a.result.getHoverName().copy().withStyle(ChatFormatting.GREEN))
					.append(Component.literal("!").withStyle(ChatFormatting.WHITE)));
		}
		hideBar(a);
		if (a.oneUse) {
			altars.remove(key(a.dim, a.pos));
		} else {
			a.active = false;
			a.starter = null;
			a.ticksLeft = 0;
			refreshHologram(a);
		}
		save();
	}

	/** Remove an altar. If a ritual was running the consumed items are dropped back at the altar. */
	public void removeAltar(Altar a, boolean announce) {
		ServerLevel w = server.getLevel(a.dim);
		boolean wasActive = a.active;
		if (w != null) {
			removeDisplays(w, a);
			if (wasActive) {
				for (ItemStack s : a.recipe) dropSplit(w, a.pos.above(), s);
			}
		}
		hideBar(a);
		altars.remove(key(a.dim, a.pos));
		if (wasActive && announce) {
			broadcast(prefix().append(Component.literal("Ritual cancelled. Altar removed.").withStyle(ChatFormatting.RED)));
		}
		save();
	}

	public void cancelFor(UUID player) {
		if (stopping || !toggle("cancel")) return;
		for (Altar a : new ArrayList<>(altars.values())) {
			if (!a.active || !player.equals(a.starter)) continue;
			if (a.restartOnDeath) resetRitual(a);
			else removeAltar(a, true);
		}
	}

	public int clearAll() {
		int n = altars.size();
		for (Altar a : new ArrayList<>(altars.values())) removeAltar(a, true);
		return n;
	}

	private void hideBar(Altar a) {
		if (a.bar != null) {
			a.bar.removeAllPlayers();
			a.bar.setVisible(false);
			a.bar = null;
		}
	}

	public void shutdown() {
		stopping = true;
		save();
		for (Altar a : altars.values()) hideBar(a);
	}

	// ------------------------------------------------------------------ ticking

	public void tick() {
		if (!later.isEmpty()) {
			List<Runnable> run = new ArrayList<>(later);
			later.clear();
			for (Runnable r : run) r.run();
		}
		if (altars.isEmpty()) return;
		tickCounter++;
		for (Altar a : new ArrayList<>(altars.values())) {
			ServerLevel w = server.getLevel(a.dim);
			if (w == null) continue;
			boolean loaded = w.hasChunkAt(a.pos);

			if (loaded && (tickCounter + a.id) % 20 == 0 && w.getBlockState(a.pos).isAir()) {
				removeAltar(a, true);
				continue;
			}

			if (a.active) {
				if (a.ticksLeft > 0) a.ticksLeft--;
				if (loaded && a.ticksLeft <= 0) {
					complete(w, a);
					continue;
				}
				if ((tickCounter + a.id) % 20 == 0) {
					updateBar(a);
					updateText(w, a);
				}
				if (loaded && toggle("particles") && tickCounter % 4 == 0) {
					double cx = a.pos.getX() + 0.5, cy = a.pos.getY() + 1.1, cz = a.pos.getZ() + 0.5;
					w.sendParticles(ParticleTypes.FLAME, cx, cy, cz, 6, 0.3, 0.2, 0.3, 0.02);
					w.sendParticles(ParticleTypes.LARGE_SMOKE, cx, cy + 0.2, cz, 3, 0.3, 0.3, 0.3, 0.01);
				}
			}

			if (loaded && (tickCounter + a.id) % 40 == 0) ensureHologram(w, a);
		}
		if (tickCounter % 200 == 0) {
			for (Altar a : altars.values()) {
				if (a.active) {
					save();
					break;
				}
			}
		}
	}

	private void updateBar(Altar a) {
		if (!toggle("bossbar")) {
			if (a.bar != null) a.bar.setVisible(false);
			return;
		}
		if (a.bar == null) {
			a.bar = new ServerBossEvent(UUID.randomUUID(), Component.empty(), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);
		}
		a.bar.setVisible(true);
		int secs = (a.ticksLeft + 19) / 20;
		a.bar.setName(Component.literal("Forging: ").withStyle(ChatFormatting.RED)
				.append(a.result.getHoverName().copy().withStyle(ChatFormatting.GOLD))
				.append(Component.literal(" (" + secs + "s)").withStyle(ChatFormatting.GRAY)));
		a.bar.setProgress(a.totalTicks <= 0 ? 0f : Math.max(0f, Math.min(1f, (float) a.ticksLeft / a.totalTicks)));
		for (ServerPlayer pl : server.getPlayerList().getPlayers()) a.bar.addPlayer(pl);
	}

	// ------------------------------------------------------------------ hologram (floating item + text)

	private MutableComponent hologramText(Altar a) {
		MutableComponent t = Component.literal("");
		MutableComponent title = a.result.getHoverName().copy().withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
		if (a.result.getCount() > 1) title.append(Component.literal(" x" + a.result.getCount()).withStyle(ChatFormatting.YELLOW));
		t.append(title);
		if (a.recipe.isEmpty()) {
			t.append(Component.literal("\nNo requirements").withStyle(ChatFormatting.GRAY));
		}
		for (ItemStack s : a.recipe) {
			t.append(Component.literal("\n• ").withStyle(ChatFormatting.WHITE));
			t.append(Component.literal(s.getCount() + "x ").withStyle(ChatFormatting.WHITE));
			t.append(s.getHoverName().copy().withStyle(ChatFormatting.GOLD));
		}
		if (a.active) {
			int secs = (a.ticksLeft + 19) / 20;
			t.append(Component.literal(String.format(Locale.ROOT, "\n\nForging... %d:%02d", secs / 60, secs % 60))
					.withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
		}
		return t;
	}

	/** The hologram text as SNBT, ready to drop into a summon / data merge command. */
	private String textSnbt(Altar a) {
		RegistryOps<Tag> o = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
		return ComponentSerialization.CODEC.encodeStart(o, hologramText(a)).result().map(Tag::toString).orElse("\"\"");
	}

	private void runCommand(ServerLevel w, Vec3 at, String cmd) {
		CommandSourceStack src = server.createCommandSourceStack().withLevel(w).withPosition(at).withSuppressedOutput();
		server.getCommands().performPrefixedCommand(src, cmd);
	}

	private void updateText(ServerLevel w, Altar a) {
		if (!toggle("hologram")) return;
		Vec3 at = new Vec3(a.pos.getX() + 0.5, a.pos.getY() + 2.4, a.pos.getZ() + 0.5);
		runCommand(w, at, "data merge entity @e[tag=" + TAG_TEXT + a.id + ",limit=1] {text:" + textSnbt(a) + "}");
	}

	public void removeDisplays(ServerLevel w, Altar a) {
		AABB box = new AABB(a.pos).inflate(4);
		String ti = TAG_ITEM + a.id, tt = TAG_TEXT + a.id;
		for (Entity e : w.getEntitiesOfClass(Entity.class, box,
				e -> e.entityTags().contains(ti) || e.entityTags().contains(tt))) {
			e.discard();
		}
	}

	/** Rebuild the hologram from scratch (used after the result / recipe changes). */
	public void refreshHologram(Altar a) {
		ServerLevel w = server.getLevel(a.dim);
		if (w == null || !w.hasChunkAt(a.pos)) return;
		removeDisplays(w, a);
		ensureHologram(w, a);
	}

	private void ensureHologram(ServerLevel w, Altar a) {
		if (!toggle("hologram") || a.result.isEmpty()) {
			removeDisplays(w, a);
			return;
		}
		AABB box = new AABB(a.pos).inflate(4);
		String ti = TAG_ITEM + a.id, tt = TAG_TEXT + a.id;

		boolean hasItem = !w.getEntitiesOfClass(ItemEntity.class, box, e -> e.entityTags().contains(ti)).isEmpty();
		if (!hasItem) {
			ItemEntity ie = new ItemEntity(w, a.pos.getX() + 0.5, a.pos.getY() + 1.35, a.pos.getZ() + 0.5,
					a.result.copyWithCount(1));
			ie.setNoGravity(true);
			ie.setDeltaMovement(Vec3.ZERO);
			ie.setPickUpDelay(32767);
			ie.setExtendedLifetime();
			ie.setPermanentlyInvulnerable(true);
			ie.addTag(ti);
			w.addFreshEntity(ie);
		}

		boolean hasText = !w.getEntitiesOfClass(Entity.class, box, e -> e.entityTags().contains(tt)).isEmpty();
		if (!hasText) {
			Vec3 at = new Vec3(a.pos.getX() + 0.5, a.pos.getY() + 2.4, a.pos.getZ() + 0.5);
			String cmd = String.format(Locale.ROOT, "summon text_display %.2f %.2f %.2f ", at.x, at.y, at.z)
					+ "{Tags:[\"altar_mod\",\"" + tt + "\"],billboard:\"center\",text:" + textSnbt(a)
					+ ",line_width:300,background:0,shadow:1b,"
					+ "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
					+ "translation:[0f,0f,0f],scale:[1.5f,1.5f,1.5f]}}";
			runCommand(w, at, cmd);
		}
	}
}
