package com.altar;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One configured altar block plus (optionally) a ritual that is currently running on it. */
public class Altar {
	public final int id;
	public final ResourceKey<Level> dim;
	public final BlockPos pos;

	/** The item this altar forges (copied from the admin's main hand). */
	public ItemStack result = ItemStack.EMPTY;
	/** Required items, already merged / normalised. */
	public List<ItemStack> recipe = new ArrayList<>();
	/** Ritual length in seconds. */
	public int seconds = 900;
	/** true = altar is removed once its ritual finishes; false = permanent altar. */
	public boolean oneUse = true;
	/** What happens if the starter dies / disconnects: false = altar disappears, true = ritual resets (altar stays). */
	public boolean restartOnDeath = false;

	// --- ritual state ---
	public boolean active = false;
	public UUID starter;
	public String starterName = "";
	public int ticksLeft = 0;
	public int totalTicks = 0;
	public transient ServerBossEvent bar;

	public Altar(int id, ResourceKey<Level> dim, BlockPos pos) {
		this.id = id;
		this.dim = dim;
		this.pos = pos.immutable();
	}
}
