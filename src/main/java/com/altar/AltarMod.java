package com.altar;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AltarMod implements ModInitializer {
	public static final String MOD_ID = "altar";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final String USAGE =
			"Use /altar to open the altar admin menu.";

	@Override
	public void onInitialize() {
		// ---- lifecycle ----
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			AltarManager.INSTANCE = new AltarManager(server);
			AltarManager.INSTANCE.load();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (AltarManager.INSTANCE != null) AltarManager.INSTANCE.shutdown();
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> AltarManager.INSTANCE = null);
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (AltarManager.INSTANCE != null) AltarManager.INSTANCE.tick();
		});

		// ---- right-click an altar to start the ritual ----
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
			AltarManager m = AltarManager.INSTANCE;
			if (m == null || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
			Altar a = m.at(level.dimension(), hit.getBlockPos());
			if (a == null) return InteractionResult.PASS;
			// Shift-right-click opens this altar's admin settings for players with
			// the same permission required by /altar. Ordinary clicks still start rituals.
			if (sp.isShiftKeyDown()
					&& sp.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
				m.openSettings(sp, a);
			} else {
				m.tryStart(sp, a);
			}
			return InteractionResult.SUCCESS;
		});

		// ---- altars cannot be broken ----
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
			if (level.isClientSide()) return true;
			AltarManager m = AltarManager.INSTANCE;
			if (m == null || m.at(level.dimension(), pos) == null || !m.toggle("protect")) return true;
			player.sendSystemMessage(Component.literal("This altar cannot be broken.").withStyle(ChatFormatting.RED));
			return false;
		});

		// ---- starter dies / disconnects => ritual cancelled ----
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer p && AltarManager.INSTANCE != null)
				AltarManager.INSTANCE.cancelFor(p.getUUID());
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (AltarManager.INSTANCE != null) AltarManager.INSTANCE.cancelFor(handler.getPlayer().getUUID());
		});

		// ---- commands ----
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(Commands.literal("altar")
						.requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
						.executes(this::dashboard)
						.then(Commands.literal("set")
								.executes(ctx -> set(ctx, 900))
								.then(Commands.argument("seconds", IntegerArgumentType.integer(1, 86400))
										.executes(ctx -> set(ctx, IntegerArgumentType.getInteger(ctx, "seconds")))))
						.then(Commands.literal("settings").executes(this::settings))
						.then(Commands.literal("recipe").executes(this::recipe))
						.then(Commands.literal("clear").executes(this::clear))
						.then(Commands.literal("clearall").executes(this::clearAll))
						.then(Commands.literal("toggle")
								.then(Commands.argument("toggle", StringArgumentType.word())
										.suggests((ctx, b) -> SharedSuggestionProvider.suggest(AltarManager.TOGGLE_DEFAULTS.keySet(), b))
										.executes(ctx -> toggle(ctx, null))
										.then(Commands.argument("value", BoolArgumentType.bool())
												.executes(ctx -> toggle(ctx, BoolArgumentType.getBool(ctx, "value"))))))));

		LOGGER.info("Altar mod loaded.");
	}

	// ------------------------------------------------------------------ command handlers

	private int dashboard(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		AltarManager.INSTANCE.openDashboard(p);
		return 1;
	}

	private static int usage(CommandSourceStack src) {
		src.sendFailure(Component.literal(USAGE));
		return 0;
	}

	/** The block the player is looking at within 6 blocks, or null. */
	private static BlockPos lookedAt(ServerPlayer p) {
		HitResult h = p.pick(6.0, 1.0f, false);
		if (h instanceof BlockHitResult b && h.getType() == HitResult.Type.BLOCK) return b.getBlockPos();
		return null;
	}

	private int set(CommandContext<CommandSourceStack> ctx, int seconds) throws CommandSyntaxException {
		CommandSourceStack src = ctx.getSource();
		ServerPlayer p = src.getPlayerOrException();
		AltarManager m = AltarManager.INSTANCE;
		BlockPos pos = lookedAt(p);
		if (pos == null) {
			src.sendFailure(Component.literal("Look at the block you want to set as the altar (within 6 blocks)."));
			return 0;
		}
		ItemStack held = p.getMainHandItem();
		if (held.isEmpty()) {
			src.sendFailure(Component.literal("Hold the item you want this altar to forge in your main hand."));
			return 0;
		}
		return m.setAltar(p, pos, held, seconds) ? 1 : 0;
	}

	private int settings(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack src = ctx.getSource();
		ServerPlayer p = src.getPlayerOrException();
		AltarManager m = AltarManager.INSTANCE;
		BlockPos pos = lookedAt(p);
		Altar a = pos == null ? null : m.at(((ServerLevel) p.level()).dimension(), pos);
		if (a == null) {
			src.sendFailure(Component.literal("That block is not an altar."));
			return 0;
		}
		m.openSettings(p, a);
		return 1;
	}

	private int recipe(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack src = ctx.getSource();
		ServerPlayer p = src.getPlayerOrException();
		AltarManager m = AltarManager.INSTANCE;
		BlockPos pos = lookedAt(p);
		Altar a = pos == null ? null : m.at(((ServerLevel) p.level()).dimension(), pos);
		if (a == null) {
			src.sendFailure(Component.literal("That block is not an altar."));
			return 0;
		}
		m.openEditor(p, a);
		return 1;
	}

	private int clear(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack src = ctx.getSource();
		ServerPlayer p = src.getPlayerOrException();
		AltarManager m = AltarManager.INSTANCE;
		BlockPos pos = lookedAt(p);
		Altar a = pos == null ? null : m.at(((ServerLevel) p.level()).dimension(), pos);
		if (a == null) {
			src.sendFailure(Component.literal("That block is not an altar."));
			return 0;
		}
		boolean wasActive = a.active;
		m.removeAltar(a, true);
		if (!wasActive) src.sendSuccess(() -> Component.literal("Altar removed.").withStyle(ChatFormatting.GREEN), false);
		return 1;
	}

	private int clearAll(CommandContext<CommandSourceStack> ctx) {
		int n = AltarManager.INSTANCE.clearAll();
		ctx.getSource().sendSuccess(() -> Component.literal("Cleared " + n + " altar(s).").withStyle(ChatFormatting.GREEN), false);
		return n;
	}

	private int toggle(CommandContext<CommandSourceStack> ctx, Boolean value) {
		String name = StringArgumentType.getString(ctx, "toggle").toLowerCase();
		if (!AltarManager.TOGGLE_DEFAULTS.containsKey(name)) {
			ctx.getSource().sendFailure(Component.literal("Unknown toggle '" + name + "'. Options: "
					+ String.join(", ", AltarManager.TOGGLE_DEFAULTS.keySet())));
			return 0;
		}
		AltarManager m = AltarManager.INSTANCE;
		boolean v = value != null ? value : !m.toggle(name);
		m.toggles.put(name, v);
		m.save();
		ctx.getSource().sendSuccess(() -> Component.literal("Toggle ").withStyle(ChatFormatting.GREEN)
				.append(Component.literal(name).withStyle(ChatFormatting.YELLOW))
				.append(Component.literal(" is now ").withStyle(ChatFormatting.GREEN))
				.append(Component.literal(String.valueOf(v)).withStyle(v ? ChatFormatting.GREEN : ChatFormatting.RED)), false);
		return 1;
	}
}
