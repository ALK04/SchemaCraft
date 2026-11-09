package com.schemacraft.schematic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Un schéma : une boîte de blocs (sizeX x sizeY x sizeZ) stockée avec une palette.
 * Les coordonnées locales partent de (0,0,0) = coin minimum de la sélection d'origine.
 */
public final class Schematic {
	public static final int FORMAT_VERSION = 1;

	private final String name;
	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	/** Index 0 = air. */
	private final List<BlockState> palette;
	/** Index de palette pour chaque bloc, ordre (y * sizeZ + z) * sizeX + x. */
	private final int[] blocks;
	private final int nonAirCount;

	private Schematic(String name, int sizeX, int sizeY, int sizeZ, List<BlockState> palette, int[] blocks) {
		this.name = name;
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.palette = palette;
		this.blocks = blocks;
		int count = 0;
		for (int b : blocks) {
			if (b != 0 && !palette.get(b).isAir()) count++;
		}
		this.nonAirCount = count;
	}

	/** Construit un schéma à partir de données déjà lues (import .litematic...). */
	public static Schematic of(String name, int sizeX, int sizeY, int sizeZ, List<BlockState> palette, int[] blocks) {
		if (blocks.length != sizeX * sizeY * sizeZ) throw new IllegalArgumentException("Nombre de blocs incohérent");
		return new Schematic(name, sizeX, sizeY, sizeZ, palette, blocks);
	}

	/** Copie les blocs du monde (côté client) compris entre deux coins, inclus. */
	public static Schematic capture(Level level, BlockPos a, BlockPos b, String name) {
		BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
		int sx = Math.abs(a.getX() - b.getX()) + 1;
		int sy = Math.abs(a.getY() - b.getY()) + 1;
		int sz = Math.abs(a.getZ() - b.getZ()) + 1;

		List<BlockState> palette = new ArrayList<>();
		Map<BlockState, Integer> ids = new HashMap<>();
		palette.add(Blocks.AIR.defaultBlockState());
		ids.put(Blocks.AIR.defaultBlockState(), 0);

		int[] blocks = new int[sx * sy * sz];
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int y = 0; y < sy; y++) {
			for (int z = 0; z < sz; z++) {
				for (int x = 0; x < sx; x++) {
					p.set(min.getX() + x, min.getY() + y, min.getZ() + z);
					BlockState state = level.getBlockState(p);
					int id;
					if (state.isAir()) {
						id = 0;
					} else {
						Integer existing = ids.get(state);
						if (existing == null) {
							id = palette.size();
							palette.add(state);
							ids.put(state, id);
						} else {
							id = existing;
						}
					}
					blocks[(y * sz + z) * sx + x] = id;
				}
			}
		}
		return new Schematic(name, sx, sy, sz, palette, blocks);
	}

	public BlockState get(int x, int y, int z) {
		if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
			return Blocks.AIR.defaultBlockState();
		}
		return palette.get(blocks[(y * sizeZ + z) * sizeX + x]);
	}

	/**
	 * Copie miroir + pivotée du schéma (même logique que Litematica : miroir puis rotation,
	 * et BlockState#mirror / #rotate pour orienter escaliers, bûches, portes...).
	 * La boîte résultante repart de (0,0,0) : le coin du placement ne bouge pas.
	 */
	public Schematic transformed(Rotation rotation, Mirror mirror) {
		if (rotation == Rotation.NONE && mirror == Mirror.NONE) return this;

		boolean swap = rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90;
		int nsx = swap ? sizeZ : sizeX;
		int nsz = swap ? sizeX : sizeZ;

		List<BlockState> newPalette = new ArrayList<>(palette.size());
		for (BlockState state : palette) {
			newPalette.add(state.mirror(mirror).rotate(rotation));
		}

		int[] newBlocks = new int[blocks.length];
		for (int y = 0; y < sizeY; y++) {
			for (int z = 0; z < sizeZ; z++) {
				for (int x = 0; x < sizeX; x++) {
					// 1) miroir
					int mx = mirror == Mirror.FRONT_BACK ? sizeX - 1 - x : x;
					int mz = mirror == Mirror.LEFT_RIGHT ? sizeZ - 1 - z : z;
					// 2) rotation autour de l'axe Y, ramenée dans la boîte [0, taille[
					int nx, nz;
					switch (rotation) {
						case CLOCKWISE_90 -> { nx = sizeZ - 1 - mz; nz = mx; }
						case CLOCKWISE_180 -> { nx = sizeX - 1 - mx; nz = sizeZ - 1 - mz; }
						case COUNTERCLOCKWISE_90 -> { nx = mz; nz = sizeX - 1 - mx; }
						default -> { nx = mx; nz = mz; }
					}
					newBlocks[(y * nsz + nz) * nsx + nx] = blocks[(y * sizeZ + z) * sizeX + x];
				}
			}
		}
		return new Schematic(name, nsx, sizeY, nsz, newPalette, newBlocks);
	}

	public CompoundTag toNbt() {
		CompoundTag tag = new CompoundTag();
		tag.putInt("Version", FORMAT_VERSION);
		tag.putIntArray("Size", new int[] {sizeX, sizeY, sizeZ});
		ListTag paletteTag = new ListTag();
		for (BlockState state : palette) {
			paletteTag.add(NbtUtils.writeBlockState(state));
		}
		tag.put("Palette", paletteTag);
		tag.putIntArray("Blocks", blocks);
		return tag;
	}

	public static Schematic fromNbt(CompoundTag tag, String name) {
		int[] size = tag.getIntArray("Size").orElseThrow(() -> new IllegalArgumentException("Size manquant"));
		if (size.length != 3) throw new IllegalArgumentException("Size invalide");
		ListTag paletteTag = tag.getListOrEmpty("Palette");
		List<BlockState> palette = new ArrayList<>(paletteTag.size());
		for (int i = 0; i < paletteTag.size(); i++) {
			palette.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK, paletteTag.getCompoundOrEmpty(i)));
		}
		if (palette.isEmpty()) palette.add(Blocks.AIR.defaultBlockState());
		int[] blocks = tag.getIntArray("Blocks").orElseThrow(() -> new IllegalArgumentException("Blocks manquant"));
		if (blocks.length != size[0] * size[1] * size[2]) throw new IllegalArgumentException("Nombre de blocs incohérent");
		for (int i = 0; i < blocks.length; i++) {
			if (blocks[i] < 0 || blocks[i] >= palette.size()) blocks[i] = 0;
		}
		return new Schematic(name, size[0], size[1], size[2], palette, blocks);
	}

	public String name() { return name; }
	public int sizeX() { return sizeX; }
	public int sizeY() { return sizeY; }
	public int sizeZ() { return sizeZ; }
	public int nonAirCount() { return nonAirCount; }
}
