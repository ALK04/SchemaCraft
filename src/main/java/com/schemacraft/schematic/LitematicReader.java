package com.schemacraft.schematic;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Lecture des fichiers .litematic (format de Litematica, NBT compressé) :
 * <pre>
 * Regions: { nom: { Position{x,y,z}, Size{x,y,z} (peut être négatif),
 *                   BlockStatePalette: [ {Name, Properties} ], BlockStates: long[] } }
 * </pre>
 * BlockStates = tableau de bits compact (LitematicaBitArray) : chaque entrée fait
 * max(2, bits nécessaires pour la palette) bits et peut chevaucher deux longs.
 * Ordre des index : (y * sizeZ + z) * sizeX + x, depuis le coin minimum de la région.
 * Toutes les régions sont fusionnées dans une seule boîte englobante.
 * Les blocs-entités (contenu des coffres...) et entités sont ignorés.
 */
public final class LitematicReader {
	public static final String EXTENSION = ".litematic";

	private LitematicReader() {}

	private record Region(int minX, int minY, int minZ, int sx, int sy, int sz, List<BlockState> palette, long[] data, int bits) {}

	public static Schematic read(Path file, String name) throws IOException {
		CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
		CompoundTag regionsTag = root.getCompoundOrEmpty("Regions");
		if (regionsTag.keySet().isEmpty()) throw new IOException("aucune région dans le fichier");

		List<Region> regions = new ArrayList<>();
		for (String key : regionsTag.keySet()) {
			Region r = readRegion(regionsTag.getCompoundOrEmpty(key));
			if (r != null) regions.add(r);
		}
		if (regions.isEmpty()) throw new IOException("régions vides ou illisibles");

		// Boîte englobante de toutes les régions.
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (Region r : regions) {
			minX = Math.min(minX, r.minX); minY = Math.min(minY, r.minY); minZ = Math.min(minZ, r.minZ);
			maxX = Math.max(maxX, r.minX + r.sx); maxY = Math.max(maxY, r.minY + r.sy); maxZ = Math.max(maxZ, r.minZ + r.sz);
		}
		int sx = maxX - minX, sy = maxY - minY, sz = maxZ - minZ;
		long volume = (long) sx * sy * sz;
		if (volume > 64L * 1024 * 1024) throw new IOException("schéma trop grand (" + sx + "x" + sy + "x" + sz + ")");

		List<BlockState> palette = new ArrayList<>();
		Map<BlockState, Integer> ids = new HashMap<>();
		BlockState air = Blocks.AIR.defaultBlockState();
		palette.add(air);
		ids.put(air, 0);
		int[] blocks = new int[(int) volume];

		for (Region r : regions) {
			// Correspondance palette de la région -> palette globale.
			int[] map = new int[r.palette.size()];
			for (int i = 0; i < map.length; i++) {
				BlockState state = r.palette.get(i);
				if (state.isAir()) {
					map[i] = 0;
				} else {
					Integer id = ids.get(state);
					if (id == null) {
						id = palette.size();
						palette.add(state);
						ids.put(state, id);
					}
					map[i] = id;
				}
			}
			long mask = (1L << r.bits) - 1L;
			int ox = r.minX - minX, oy = r.minY - minY, oz = r.minZ - minZ;
			int index = 0;
			for (int y = 0; y < r.sy; y++) {
				for (int z = 0; z < r.sz; z++) {
					for (int x = 0; x < r.sx; x++, index++) {
						int value = (int) getAt(r.data, index, r.bits, mask);
						if (value <= 0 || value >= map.length) continue;
						int id = map[value];
						if (id == 0) continue;
						blocks[((y + oy) * sz + (z + oz)) * sx + (x + ox)] = id;
					}
				}
			}
		}
		return Schematic.of(name, sx, sy, sz, palette, blocks);
	}

	private static Region readRegion(CompoundTag tag) {
		CompoundTag pos = tag.getCompoundOrEmpty("Position");
		CompoundTag size = tag.getCompoundOrEmpty("Size");
		int px = pos.getIntOr("x", 0), py = pos.getIntOr("y", 0), pz = pos.getIntOr("z", 0);
		int sx = size.getIntOr("x", 0), sy = size.getIntOr("y", 0), sz = size.getIntOr("z", 0);
		if (sx == 0 || sy == 0 || sz == 0) return null;

		// Une taille négative veut dire que la région s'étend dans l'autre sens depuis Position.
		int minX = sx < 0 ? px + sx + 1 : px;
		int minY = sy < 0 ? py + sy + 1 : py;
		int minZ = sz < 0 ? pz + sz + 1 : pz;
		sx = Math.abs(sx); sy = Math.abs(sy); sz = Math.abs(sz);

		ListTag paletteTag = tag.getListOrEmpty("BlockStatePalette");
		List<BlockState> palette = new ArrayList<>(paletteTag.size());
		for (int i = 0; i < paletteTag.size(); i++) {
			// Bloc inconnu dans cette version -> air.
			palette.add(BlockStates.fromNbt(paletteTag.getCompoundOrEmpty(i)));
		}
		if (palette.isEmpty()) return null;

		long[] data = tag.getLongArray("BlockStates").orElse(new long[0]);
		int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(palette.size() - 1));
		long needed = ((long) sx * sy * sz * bits + 63) / 64;
		if (data.length < needed) return null;
		return new Region(minX, minY, minZ, sx, sy, sz, palette, data, bits);
	}

	/** Même lecture que LitematicaBitArray#getAt. */
	private static long getAt(long[] data, int index, int bits, long mask) {
		long startOffset = (long) index * bits;
		int startArr = (int) (startOffset >> 6);
		int endArr = (int) ((((long) index + 1) * bits - 1) >> 6);
		int startBit = (int) (startOffset & 0x3F);
		if (startArr == endArr) {
			return (data[startArr] >>> startBit) & mask;
		}
		int endOffset = 64 - startBit;
		return ((data[startArr] >>> startBit) | (data[endArr] << endOffset)) & mask;
	}
}
