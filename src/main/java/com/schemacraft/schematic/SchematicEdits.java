package com.schemacraft.schematic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Modifications d'un schéma (onglet Édition). Chaque opération renvoie un nouveau
 * {@link Schematic} : l'ancien reste intact, ce qui permet d'annuler.
 * Quand un bloc change, les propriétés communes (orientation, moitié, eau...) sont gardées.
 */
public final class SchematicEdits {
	private SchematicEdits() {}

	/** Un type de bloc présent dans le schéma (toutes orientations confondues). */
	public record Entry(Block block, String id, int count) {}

	/** Une part d'un mélange : bloc et poids (pourcentage). */
	public record MixPart(Block block, String id, int weight) {}

	/** Résultat d'un remplacement par mot. */
	public record WordResult(Schematic schematic, int replaced, List<String> changes, List<String> skipped) {}

	/** Blocs (hors air) du schéma, du plus fréquent au moins fréquent. */
	public static List<Entry> entries(Schematic s) {
		List<BlockState> palette = s.palette();
		int[] perIndex = new int[palette.size()];
		for (int b : s.blocksCopy()) perIndex[b]++;
		Map<Block, int[]> counts = new LinkedHashMap<>();
		for (int i = 0; i < palette.size(); i++) {
			BlockState state = palette.get(i);
			if (perIndex[i] == 0 || state.isAir()) continue;
			counts.computeIfAbsent(state.getBlock(), k -> new int[1])[0] += perIndex[i];
		}
		List<Entry> out = new ArrayList<>();
		counts.forEach((block, n) -> out.add(new Entry(block, BlockStates.idOf(block), n[0])));
		out.sort((a, b) -> b.count() != a.count() ? Integer.compare(b.count(), a.count()) : a.id().compareTo(b.id()));
		return out;
	}

	// ================================================================ Remplacer par mot

	/** Si le résultat n'existe pas, essais de remplacement (ex. crimson_log -> crimson_stem). */
	private static final String[][] ALIASES = {
		{"_log", "_stem"}, {"_wood", "_hyphae"}, {"_stem", "_log"}, {"_hyphae", "_wood"},
	};
	private static final Map<String, String> SPECIAL = Map.of(
		"crimson_leaves", "nether_wart_block",
		"warped_leaves", "warped_wart_block");

	/** Nombre de types de blocs du schéma dont le nom contient ce mot. */
	public static int typesWithWord(List<Entry> entries, String word) {
		String w = BlockStates.normalize(word);
		int n = 0;
		for (Entry e : entries) if (replaceWord(e.id(), w, "x") != null) n++;
		return n;
	}

	/**
	 * Remplace un mot dans le nom de chaque bloc, mot entier entre "_" :
	 * "oak" -> "spruce" change oak_log, stripped_oak_log, oak_stairs... mais pas dark_oak_log
	 * (dark_spruce_log n'existe pas : il est ignoré et listé dans {@code skipped}).
	 * Un remplaçant vide enlève le mot ("stripped" -> "" : stripped_oak_log -> oak_log).
	 */
	public static WordResult replaceWords(Schematic s, String find, String repl) {
		String f = BlockStates.normalize(find), r = BlockStates.normalize(repl);
		List<BlockState> palette = s.palette();
		List<BlockState> out = new ArrayList<>(palette.size());
		Map<String, String> changes = new LinkedHashMap<>();
		List<String> skipped = new ArrayList<>();
		boolean[] changed = new boolean[palette.size()];
		for (int i = 0; i < palette.size(); i++) {
			BlockState state = palette.get(i);
			String id = BlockStates.idOf(state);
			String target = state.isAir() || f.isEmpty() ? null : replaceWord(id, f, r);
			if (target == null || target.equals(id)) {
				out.add(state);
				continue;
			}
			target = resolveAlias(target);
			Block block = BlockStates.block(target);
			if (block == null) {
				if (!skipped.contains(target)) skipped.add(target);
				out.add(state);
			} else {
				out.add(BlockStates.withBlock(state, block));
				changes.put(id, target);
				changed[i] = true;
			}
		}
		int[] blocks = s.blocksCopy();
		int count = 0;
		for (int b : blocks) if (changed[b]) count++;
		List<String> changeList = new ArrayList<>();
		changes.forEach((a, b) -> changeList.add(a + " → " + b));
		return new WordResult(compact(s, out, blocks), count, changeList, skipped);
	}

	static String replaceWord(String id, String find, String repl) {
		if (find.isEmpty()) return null;
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
		if (BlockStates.exists(id)) return id;
		String special = SPECIAL.get(id);
		if (special != null && BlockStates.exists(special)) return special;
		for (String[] a : ALIASES) {
			if (id.endsWith(a[0])) {
				String alt = id.substring(0, id.length() - a[0].length()) + a[1];
				if (BlockStates.exists(alt)) return alt;
			}
		}
		return id;
	}

	// ================================================================ Remplacer / mélanger un bloc

	private static final Pattern PART = Pattern.compile("^([a-z0-9_.\\-/:]+)(?:\\s+(\\d{1,3})\\s*%?)?$");

	/**
	 * Lit "spruce_planks" ou "stone 60, andesite 30, cobblestone 10" (le % est facultatif).
	 * Sans pourcentages, les blocs sont répartis également. "air" = retirer.
	 * @throws IllegalArgumentException avec un message lisible si le texte est invalide
	 */
	public static List<MixPart> parseMix(String text) {
		List<MixPart> parts = new ArrayList<>();
		int withWeight = 0;
		for (String raw : text.split("[,;+]")) {
			String p = raw.trim().toLowerCase(java.util.Locale.ROOT);
			if (p.isEmpty()) continue;
			Matcher m = PART.matcher(p);
			if (!m.matches()) throw new IllegalArgumentException("« " + raw.trim() + " » : écris le bloc puis son %, ex. stone 60");
			String id = BlockStates.normalize(m.group(1));
			Block block = BlockStates.block(id);
			if (block == null) throw new IllegalArgumentException("« " + id + " » n'existe pas");
			int w = m.group(2) == null ? -1 : Integer.parseInt(m.group(2));
			if (w >= 0) withWeight++;
			parts.add(new MixPart(block, id, w));
		}
		if (parts.isEmpty()) throw new IllegalArgumentException("écris un bloc, ex. stone");
		if (withWeight == 0) {
			List<MixPart> even = new ArrayList<>();
			for (MixPart p : parts) even.add(new MixPart(p.block(), p.id(), 1));
			return even;
		}
		if (withWeight != parts.size()) throw new IllegalArgumentException("donne un % à chaque bloc du mélange");
		int total = 0;
		for (MixPart p : parts) total += p.weight();
		if (total <= 0) throw new IllegalArgumentException("les pourcentages valent tous 0");
		return parts;
	}

	/** "stone" ou "stone 60%, andesite 40%" (pourcentages ramenés sur 100). */
	public static String describe(List<MixPart> parts) {
		if (parts.size() == 1) return parts.get(0).id();
		int total = 0;
		for (MixPart p : parts) total += p.weight();
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < parts.size(); i++) {
			if (i > 0) sb.append(", ");
			sb.append(parts.get(i).id()).append(' ').append(Math.round(100.0 * parts.get(i).weight() / total)).append('%');
		}
		return sb.toString();
	}

	/** Nombre de blocs {@code block} dans le schéma. */
	public static int count(List<Entry> entries, Block block) {
		for (Entry e : entries) if (e.block() == block) return e.count();
		return 0;
	}

	/**
	 * Remplace tous les blocs {@code from} par le mélange : proportions exactes (arrondies),
	 * positions tirées au hasard. Un seul bloc = simple remplacement.
	 */
	public static Schematic mix(Schematic s, Block from, List<MixPart> parts, Random random) {
		List<BlockState> palette = s.palette();
		int[] blocks = s.blocksCopy();

		int n = 0;
		for (int b : blocks) if (palette.get(b).getBlock() == from) n++;
		int[] positions = new int[n];
		n = 0;
		for (int i = 0; i < blocks.length; i++) if (palette.get(blocks[i]).getBlock() == from) positions[n++] = i;
		for (int i = positions.length - 1; i > 0; i--) {
			int j = random.nextInt(i + 1);
			int t = positions[i];
			positions[i] = positions[j];
			positions[j] = t;
		}
		int total = 0;
		for (MixPart p : parts) total += p.weight();

		List<BlockState> newPalette = new ArrayList<>(palette);
		Map<BlockState, Integer> ids = new HashMap<>();
		for (int i = 0; i < newPalette.size(); i++) ids.putIfAbsent(newPalette.get(i), i);
		// (index de palette d'origine, part du mélange) -> nouvel index
		Map<Long, Integer> cache = new HashMap<>();

		int k = 0, acc = 0;
		for (int pi = 0; pi < parts.size(); pi++) {
			acc += parts.get(pi).weight();
			int end = pi == parts.size() - 1 ? positions.length : (int) Math.round((double) acc / total * positions.length);
			for (; k < end; k++) {
				int pos = positions[k];
				int old = blocks[pos];
				long key = ((long) old << 16) | pi;
				Integer id = cache.get(key);
				if (id == null) {
					BlockState target = BlockStates.withBlock(palette.get(old), parts.get(pi).block());
					if (target.isAir()) {
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
		return compact(s, newPalette, blocks);
	}

	/** Retire tous les blocs {@code block} (remplacés par de l'air). */
	public static Schematic remove(Schematic s, Block block) {
		List<BlockState> palette = s.palette();
		List<BlockState> out = new ArrayList<>(palette.size());
		for (BlockState state : palette) out.add(state.getBlock() == block ? Blocks.AIR.defaultBlockState() : state);
		return compact(s, out, s.blocksCopy());
	}

	/**
	 * Nouveau schéma avec une palette nettoyée : air en 0, états en double fusionnés,
	 * entrées plus utilisées supprimées (le fichier enregistré reste léger).
	 */
	private static Schematic compact(Schematic s, List<BlockState> palette, int[] blocks) {
		List<BlockState> out = new ArrayList<>();
		Map<BlockState, Integer> ids = new HashMap<>();
		BlockState air = Blocks.AIR.defaultBlockState();
		out.add(air);
		int[] remap = new int[palette.size()];
		java.util.Arrays.fill(remap, -1);
		for (int i = 0; i < blocks.length; i++) {
			int old = blocks[i];
			int id = remap[old];
			if (id < 0) {
				BlockState state = palette.get(old);
				if (state.isAir()) {
					id = 0;
				} else {
					Integer existing = ids.get(state);
					if (existing == null) {
						existing = out.size();
						out.add(state);
						ids.put(state, existing);
					}
					id = existing;
				}
				remap[old] = id;
			}
			blocks[i] = id;
		}
		return Schematic.of(s.name(), s.sizeX(), s.sizeY(), s.sizeZ(), out, blocks);
	}
}
