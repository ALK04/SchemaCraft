package com.schemacraft.schematic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;

/** Liste des ressources (objets) nécessaires pour construire un schéma. */
public final class Materials {
	private Materials() {}

	/**
	 * @param total       nombre total d'objets pour tout le schéma
	 * @param missing     objets encore à poser (si le schéma est posé dans le monde, sinon = total)
	 * @param inInventory nombre de cet objet dans l'inventaire du joueur
	 */
	public record Entry(Item item, int total, int missing, int inInventory) {
		/** Ce qu'il faut encore aller chercher. */
		public int toFetch() {
			return Math.max(0, missing - inInventory);
		}
	}

	public static List<Entry> compute(Schematic s, BlockPos origin, ClientLevel level, Player player) {
		Map<Item, int[]> counts = new LinkedHashMap<>(); // [total, missing]
		BlockPos.MutableBlockPos wp = new BlockPos.MutableBlockPos();
		boolean checkWorld = origin != null && level != null;

		for (int y = 0; y < s.sizeY(); y++) {
			for (int z = 0; z < s.sizeZ(); z++) {
				for (int x = 0; x < s.sizeX(); x++) {
					BlockState want = s.get(x, y, z);
					if (want.isAir() || isSecondaryPart(want)) continue;
					Item item = want.getBlock().asItem();
					if (item == Items.AIR) continue;

					int amount = isDoubleSlab(want) ? 2 : 1;
					int missing = amount;
					if (checkWorld) {
						wp.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
						BlockState world = level.getBlockState(wp);
						if (world == want) {
							missing = 0;
						} else if (amount == 2 && world.getBlock() == want.getBlock()) {
							missing = 1; // une demi-dalle déjà posée
						}
					}
					int[] c = counts.computeIfAbsent(item, k -> new int[2]);
					c[0] += amount;
					c[1] += missing;
				}
			}
		}

		List<Entry> out = new ArrayList<>(counts.size());
		for (Map.Entry<Item, int[]> e : counts.entrySet()) {
			out.add(new Entry(e.getKey(), e.getValue()[0], e.getValue()[1], countInInventory(player, e.getKey())));
		}
		// Ce qu'il manque le plus en premier, puis le plus gros total.
		out.sort((a, b) -> a.missing() != b.missing() ? Integer.compare(b.missing(), a.missing()) : Integer.compare(b.total(), a.total()));
		return out;
	}

	public static int countInInventory(Player player, Item item) {
		if (player == null) return 0;
		Inventory inv = player.getInventory();
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (inv.getItem(i).getItem() == item) n += inv.getItem(i).getCount();
		}
		return n;
	}

	/** Moitié haute d'une porte / plante haute, tête de lit : posée avec l'autre moitié. */
	public static boolean isSecondaryPart(BlockState state) {
		if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
			&& state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return true;
		return state.hasProperty(BlockStateProperties.BED_PART)
			&& state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD;
	}

	private static boolean isDoubleSlab(BlockState state) {
		return state.hasProperty(BlockStateProperties.SLAB_TYPE)
			&& state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE;
	}
}
