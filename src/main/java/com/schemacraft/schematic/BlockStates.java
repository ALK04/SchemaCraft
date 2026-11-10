package com.schemacraft.schematic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Blocs désignés par leur identifiant ("stone", "minecraft:oak_log", "create:shaft"...),
 * résolus directement dans le registre du jeu.
 * <p>
 * Ne passe pas par NbtUtils : en 26.x il lit les clés "id"/"properties", alors que
 * Litematica (et les anciennes versions) écrivent "Name"/"Properties". Ici les deux sont acceptés.
 */
public final class BlockStates {
	private static List<String> allIds;

	private BlockStates() {}

	/** Identifiant court : "oak_log" pour Minecraft, "mod:bloc" pour un mod. */
	public static String idOf(Block block) {
		Identifier key = BuiltInRegistries.BLOCK.getKey(block);
		return key.getNamespace().equals("minecraft") ? key.getPath() : key.toString();
	}

	public static String idOf(BlockState state) {
		return idOf(state.getBlock());
	}

	/** Met l'identifiant au propre : minuscules, espaces -> "_", sans "minecraft:". */
	public static String normalize(String id) {
		String s = id.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
		return s.startsWith("minecraft:") ? s.substring(10) : s;
	}

	/** Le bloc qui porte cet identifiant, ou null s'il n'existe pas dans cette version. */
	public static Block block(String id) {
		String n = normalize(id);
		if (n.isEmpty()) return null;
		Identifier key = Identifier.tryParse(n.contains(":") ? n : "minecraft:" + n);
		if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) return null;
		return BuiltInRegistries.BLOCK.getValue(key);
	}

	public static boolean exists(String id) {
		return block(id) != null;
	}

	/**
	 * Même état que {@code from} mais avec un autre bloc : les propriétés que le nouveau bloc
	 * possède aussi (orientation, moitié, eau...) sont gardées, les autres ignorées.
	 */
	public static BlockState withBlock(BlockState from, Block target) {
		BlockState out = target.defaultBlockState();
		for (Property<?> p : from.getProperties()) {
			Property<?> tp = target.getStateDefinition().getProperty(p.getName());
			if (tp != null) out = withValue(out, tp, valueName(from, p));
		}
		return out;
	}

	/**
	 * Lit un état écrit en NBT : {"id", "properties"} (Minecraft 26.x) ou
	 * {"Name", "Properties"} (Litematica, anciens formats). Bloc inconnu -> air.
	 */
	public static BlockState fromNbt(CompoundTag tag) {
		String name = tag.getStringOr("id", tag.getStringOr("Name", ""));
		Block block = block(name);
		if (block == null) return Blocks.AIR.defaultBlockState();
		CompoundTag props = tag.getCompound("properties").or(() -> tag.getCompound("Properties")).orElseGet(CompoundTag::new);
		BlockState state = block.defaultBlockState();
		for (String key : props.keySet()) {
			Property<?> p = block.getStateDefinition().getProperty(key);
			if (p != null) state = withValue(state, p, props.getStringOr(key, ""));
		}
		return state;
	}

	/** Tous les identifiants de blocs du jeu (mods compris), triés. */
	public static List<String> allIds() {
		if (allIds == null) {
			List<String> ids = new ArrayList<>();
			for (Block b : BuiltInRegistries.BLOCK) ids.add(idOf(b));
			ids.sort(null);
			allIds = List.copyOf(ids);
		}
		return allIds;
	}

	private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> p) {
		return p.getName(state.getValue(p));
	}

	private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> p, String value) {
		return p.getValue(value).map(v -> state.setValue(p, v)).orElse(state);
	}
}
