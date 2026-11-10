package com.schemacraft.schematic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Modifications d'un schéma (onglet Édition). Chaque opération renvoie un nouveau
 * {@link Schematic} : l'ancien reste intact, ce qui permet d'annuler.
 * Les blocs sont désignés par leur identifiant ("spruce_log", "minecraft:stone"...).
 * Quand on change de bloc, les propriétés communes (orientation, moitié, eau...) sont gardées.
 */
public final class SchematicEdits {
	private SchematicEdits() {}

	/** Un type de bloc présent dans le schéma (toutes orientations confondues). */
	public record Entry(Block block, BlockState sample, String id, int count) {}

	/** Une part d'un mélange : identifiant du bloc et poids (pourcentage). */
	public record MixPart(String id, int weight) {}

	/** Résultat d'un remplacement par mots. */
	public record WordResult(Schematic schematic, int replaced, List<String> changes, List<String> skipped) {}

	/** Blocs (hors air) du schéma, du plus fréquent au moins fréquent. */
	public static List<Entry> entries(Schematic s) {
		List<BlockState> palette = s.palette();
		int[] perIndex = new int[palette.size()];
		for (int b : s.blocksCopy()) perIndex[b]++;
		Map<Block, int[]> counts = new LinkedHashMap<>();
		Map<Block, BlockState> samples = new HashMap<>();
		for (int i = 0; i < palette.size(); i++) {
			BlockState state = palette.get(i);
			if (perIndex[i] == 0 || state.isAir()) continue;
			counts.computeIfAbsent(state.getBlock(), k -> new int[1])[0] += perIndex[i];
			samples.putIfAbsent(state.getBlock(), state);
		}
		List<Entry> out = new ArrayList<>();
		for (Map.Entry<Block, int[]> e : counts.entrySet()) {
			BlockState sample = samples.get(e.getKey());
			out.add(new Entry(e.getKey(), sample, idOf(sample), e.getValue()[0]));
		}
		out.sort((a, b) -> b.count() != a.count() ? Integer.compare(b.count(), a.count()) : a.id().compareTo(b.id()));
		return out;
	}

	/** "minecraft:oak_log" -> "oak_log" (garde l'espace de noms des blocs moddés). */
	public static String idOf(BlockState state) {
		String name = NbtUtils.writeBlockState(state).getStringOr("Name", "minecraft:air");
		return name.startsWith("minecraft:") ? name.substring(10) : name;
	}

	/** Met l'identifiant au propre : minuscules, espaces -> "_", sans "minecraft:". */
	public static String normalize(String id) {
		String s = id.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
		return s.startsWith("minecraft:") ? s.substring(10) : s;
	}

	/** Vrai si ce bloc existe dans la version du jeu. */
	public static boolean exists(String id) {
		String n = normalize(id);
		if (!n.matches("[a-z0-9_.\\-/:]+")) return false;
		return n.equals("air") || !read(n, new CompoundTag()).isAir();
	}

	/**
	 * Même état que {@code from} mais avec un autre bloc : les propriétés que le nouveau bloc
	 * possède sont gardées (les autres sont ignorées). Null si le bloc n'existe pas.
	 */
	public static BlockState withBlock(BlockState from, String id) {
		if (!exists(id)) return null;
		CompoundTag props = NbtUtils.writeBlockState(from).getCompoundOrEmpty("Properties");
		return read(normalize(id), props);
	}

	private static BlockState read(String id, CompoundTag props) {
		CompoundTag tag = new CompoundTag();
		tag.putString("Name", id.contains(":") ? id : "minecraft:" + id);
		if (!props.keySet().isEmpty()) tag.put("Properties", props.copy());
		return NbtUtils.readBlockState(BuiltInRegistries.BLOCK, tag);
	}

	// ================================================================ Remplacer par mots

	/** Si le résultat n'existe pas, essais de remplacement (ex. crimson_log -> crimson_stem). */
	private static final String[][] ALIASES = {
		{"_log", "_stem"}, {"_wood", "_hyphae"}, {"_stem", "_log"}, {"_hyphae", "_wood"},
	};
	private static final Map<String, String> SPECIAL = Map.of(
		"crimson_leaves", "nether_wart_block",
		"warped_leaves", "warped_wart_block",
		"poplar_leaves", "yellow_poplar_leaves");

	/**
	 * Remplace un mot dans le nom de chaque bloc, mot entier entre "_" :
	 * "oak" -> "spruce" change oak_log, stripped_oak_log, oak_stairs... mais pas dark_oak_log
	 * (dark_spruce_log n'existe pas : il est ignoré et listé dans {@code skipped}).
	 */
	public static WordResult replaceWords(Schematic s, String find, String repl) {
		String f = normalize(find), r = normalize(repl);
		List<BlockState> palette = s.palette();
		List<BlockState> out = new ArrayList<>(palette.size());
		Map<String, String> changes = new LinkedHashMap<>();
		List<String> skipped = new ArrayList<>();
		boolean[] changed = new boolean[palette.size()];
		for (int i = 0; i < palette.size(); i++) {
			BlockState state = palette.get(i);
			String id = idOf(state);
			String target = state.isAir() || f.isEmpty() ? null : replaceWord(id, f, r);
			if (target == null || target.equals(id)) {
				out.add(state);
				continue;
			}
			target = resolveAlias(target);
			BlockState replaced = withBlock(state, target);
			if (replaced == null) {
				if (!skipped.contains(id + " -> " + target)) skipped.add(id + " -> " + target);
				out.add(state);
			} else {
				out.add(replaced);
				changes.put(id, target);
				changed[i] = true;
			}
		}
		int[] blocks = s.blocksCopy();
		int count = 0;
		for (int b : blocks) if (changed[b]) count++;
		List<String> changeList = new ArrayList<>();
		changes.forEach((a, b) -> changeList.add(a + " -> " + b));
		return new WordResult(Schematic.of(s.name(), s.sizeX(), s.sizeY(), s.sizeZ(), out, blocks), count, changeList, skipped);
	}

	static String replaceWord(String id, String find, String repl) {
		String ns = "";
		int colon = id.indexOf(':');
		if (colon >= 0) {
			ns = id.substring(0, colon + 1);
			id = id.substring(colon + 1);
		}
		String[] tokens = id.split("_"), f = find.split("_");
		List<String> out = new ArrayList<>();
		boolean hit = false;
		for (int i = 0; i < tokens.length; ) {
			boolean match = i + f.length <= tokens.length;
			for (int j = 0; match && j < f.length; j++) match = tokens[i + j].equals(f[j]);
			if (match) {
				if (!repl.isEmpty()) out.add(repl);
				i += f.length;
				hit = true;
			} else {
				out.add(tokens[i++]);
			}
		}
		return hit ? ns + String.join("_", out) : null;
	}

	private static String resolveAlias(String id) {
		if (exists(id)) return id;
		String special = SPECIAL.get(id);
		if (special != null && exists(special)) return special;
		for (String[] a : ALIASES) {
			if (id.endsWith(a[0])) {
				String alt = id.substring(0, id.length() - a[0].length()) + a[1];
				if (exists(alt)) return alt;
			}
		}
		return id;
	}

	// ================================================================ Remplacer / mélanger un bloc

	private static final Pattern PART = Pattern.compile("^([a-z0-9_.\\-/:]+)(?:\\s+(\\d{1,3})\\s*%?)?$");

	/**
	 * Lit "spruce_log" ou "stone 60, andesite 30, cobblestone 10" (le % est facultatif).
	 * Sans pourcentages, les blocs sont répartis également. Erreur explicite sinon.
	 */
	public static List<MixPart> parseMix(String text) {
		List<MixPart> parts = new ArrayList<>();
		int withWeight = 0;
		for (String raw : text.split("[,;+]")) {
			String p = raw.trim().toLowerCase(Locale.ROOT);
			if (p.isEmpty()) continue;
			Matcher m = PART.matcher(p);
			if (!m.matches()) throw new IllegalArgumentException("« " + raw.trim() + " » : écris le nom du bloc puis son pourcentage, ex. stone 60");
			String id = normalize(m.group(1));
			if (!exists(id)) throw new IllegalArgumentException("« " + id + " » n'existe pas dans cette version");
			int w = m.group(2) == null ? -1 : Integer.parseInt(m.group(2));
			if (w >= 0) withWeight++;
			parts.add(new MixPart(id, w));
		}
		if (parts.isEmpty()) throw new IllegalArgumentException("écris le nom d'un bloc, ex. spruce_planks");
		if (withWeight == 0) {
			List<MixPart> even = new ArrayList<>();
			for (MixPart p : parts) even.add(new MixPart(p.id(), 1));
			return even;
		}
		if (withWeight != parts.size()) throw new IllegalArgumentException("donne un pourcentage à chaque bloc du mélange");
		int total = 0;
		for (MixPart p : parts) total += p.weight();
		if (total <= 0) throw new IllegalArgumentException("les pourcentages valent tous 0");
		return parts;
	}

	/**
	 * Remplace tous les blocs {@code from} par le mélange : proportions exactes (arrondies),
	 * positions tirées au hasard. Un seul bloc = simple remplacement.
	 */
	public static Schematic mix(Schematic s, Block from, List<MixPart> parts, Random random) {
		List<BlockState> palette = s.palette();
		int[] blocks = s.blocksCopy();
		List<Integer> positions = new ArrayList<>();
		for (int i = 0; i < blocks.length; i++) {
			if (palette.get(blocks[i]).getBlock() == from) positions.add(i);
		}
		for (int i = positions.size() - 1; i > 0; i--) {
			int j = random.nextInt(i + 1);
			Integer t = positions.get(i);
			positions.set(i, positions.get(j));
			positions.set(j, t);
		}
		int total = 0;
		for (MixPart p : parts) total += p.weight();

		List<BlockState> newPalette = new ArrayList<>(palette);
		Map<BlockState, Integer> ids = new HashMap<>();
		for (int i = 0; i < newPalette.size(); i++) ids.putIfAbsent(newPalette.get(i), i);
		// Cache : (index de palette d'origine, part du mélange) -> nouvel index de palette
		Map<Long, Integer> cache = new HashMap<>();

		int k = 0, acc = 0;
		for (int pi = 0; pi < parts.size(); pi++) {
			acc += parts.get(pi).weight();
			int end = pi == parts.size() - 1 ? positions.size() : (int) Math.round((double) acc / total * positions.size());
			for (; k < end; k++) {
				int pos = positions.get(k);
				int old = blocks[pos];
				long key = ((long) old << 8) | pi;
				Integer id = cache.get(key);
				if (id == null) {
					BlockState target = withBlock(palette.get(old), parts.get(pi).id());
					if (target == null || target.isAir()) {
						id = 0;
					} else {
						id = ids.get(target);
						if (id == null) {
							id = newPalette.size();
							newPalette.add(target);
							ids.put(target, id);
						}
					}
					cache.put(key, id);
				}
				blocks[pos] = id;
			}
		}
		return Schematic.of(s.name(), s.sizeX(), s.sizeY(), s.sizeZ(), newPalette, blocks);
	}

	/** Retire tous les blocs {@code block} (remplacés par de l'air). */
	public static Schematic remove(Schematic s, Block block) {
		List<BlockState> palette = s.palette();
		List<BlockState> out = new ArrayList<>(palette.size());
		for (BlockState state : palette) out.add(state.getBlock() == block ? Blocks.AIR.defaultBlockState() : state);
		return Schematic.of(s.name(), s.sizeX(), s.sizeY(), s.sizeZ(), out, s.blocksCopy());
	}
}
