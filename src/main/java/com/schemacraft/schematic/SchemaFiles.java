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

	public static Schematic load(String name) throws IOException {
		CompoundTag tag = NbtIo.readCompressed(fileFor(name), NbtAccounter.unlimitedHeap());
		return Schematic.fromNbt(tag, name);
	}

	/** Noms (sans extension) des schémas, triés alphabétiquement. */
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
		return names;
	}
}
