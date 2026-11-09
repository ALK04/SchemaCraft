package com.schemacraft.schematic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Gestion du dossier ".minecraft/schemas" de l'instance. */
public final class SchemaFiles {
	public static final String EXTENSION = ".schema";

	private SchemaFiles() {}

	public static Path dir() throws IOException {
		Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("schemas");
		Files.createDirectories(dir);
		return dir;
	}

	/** Garde uniquement lettres, chiffres, espaces, tirets et underscores. */
	public static String sanitize(String name) {
		String cleaned = name.trim().replaceAll("[^\\p{L}\\p{N} _\\-]", "_");
		if (cleaned.length() > 64) cleaned = cleaned.substring(0, 64);
		return cleaned;
	}

	public static Path fileFor(String name) throws IOException {
		return dir().resolve(sanitize(name) + EXTENSION);
	}

	public static boolean exists(String name) {
		try {
			return Files.exists(fileFor(name));
		} catch (IOException e) {
			return false;
		}
	}

	public static void save(Schematic schematic) throws IOException {
		NbtIo.writeCompressed(schematic.toNbt(), fileFor(schematic.name()));
	}

	/**
	 * Charge une entrée de {@link #list()} : soit le nom d'un .schema,
	 * soit le chemin (relatif au dossier du jeu) d'un .litematic.
	 */
	public static Schematic load(String entry) throws IOException {
		if (isLitematic(entry)) {
			Path file = Minecraft.getInstance().gameDirectory.toPath().resolve(entry);
			return LitematicReader.read(file, displayName(entry));
		}
		CompoundTag tag = NbtIo.readCompressed(fileFor(entry), NbtAccounter.unlimitedHeap());
		return Schematic.fromNbt(tag, entry);
	}

	public static boolean isLitematic(String entry) {
		return entry.endsWith(LitematicReader.EXTENSION);
	}

	/** Nom affiché : nom du fichier sans dossier ni extension. */
	public static String displayName(String entry) {
		if (!isLitematic(entry)) return entry;
		String f = entry.substring(entry.lastIndexOf('/') + 1);
		return f.substring(0, f.length() - LitematicReader.EXTENSION.length());
	}

	/**
	 * Schémas disponibles, triés alphabétiquement :
	 * les .schema de "schemas/" (par nom) puis les .litematic de "schemas/" et
	 * "schematics/" (dossier de Litematica, sous-dossiers compris) par chemin relatif.
	 */
	public static List<String> list() {
		List<String> names = new ArrayList<>();
		try (Stream<Path> files = Files.list(dir())) {
			files.filter(p -> p.getFileName().toString().endsWith(EXTENSION))
				.forEach(p -> {
					String f = p.getFileName().toString();
					names.add(f.substring(0, f.length() - EXTENSION.length()));
				});
		} catch (IOException ignored) {
		}
		names.sort(String.CASE_INSENSITIVE_ORDER);

		List<String> litematics = new ArrayList<>();
		Path game = Minecraft.getInstance().gameDirectory.toPath();
		for (String folder : new String[] {"schemas", "schematics"}) {
			Path dir = game.resolve(folder);
			if (!Files.isDirectory(dir)) continue;
			try (Stream<Path> files = Files.walk(dir, 4)) {
				files.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(LitematicReader.EXTENSION))
					.forEach(p -> litematics.add(game.relativize(p).toString().replace('\\', '/')));
			} catch (IOException ignored) {
			}
		}
		litematics.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(displayName(a), displayName(b)));
		names.addAll(litematics);
		return names;
	}
}
