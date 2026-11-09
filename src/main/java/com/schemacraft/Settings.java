package com.schemacraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

/** Paramètres persistants, stockés dans config/schemacraft.properties. */
public final class Settings {
	private Settings() {}

	/** Printer : pose automatiquement les blocs manquants du schéma autour du joueur. */
	public static boolean printerEnabled = false;
	/** Ticks d'attente entre deux actions du printer (20 ticks = 1 seconde). */
	public static int printerDelay = 2;
	/** Nombre maximum de blocs posés par action. */
	public static int printerBlocksPerAction = 1;
	/** Portée du printer en blocs (vanilla = 4.5). */
	public static double printerRange = 4.5;
	/** En mode couche par couche, n'imprimer que la couche affichée. */
	public static boolean printerOnlyVisibleLayer = true;

	public static final int DELAY_MIN = 0, DELAY_MAX = 20;
	public static final int BLOCKS_MIN = 1, BLOCKS_MAX = 8;
	public static final double RANGE_MIN = 1.0, RANGE_MAX = 6.0;

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("schemacraft.properties");
	}

	public static void load() {
		Path f = file();
		if (!Files.exists(f)) return;
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
			p.load(r);
		} catch (IOException e) {
			return;
		}
		printerEnabled = Boolean.parseBoolean(p.getProperty("printer.enabled", String.valueOf(printerEnabled)));
		printerDelay = clamp(parseInt(p.getProperty("printer.delay"), printerDelay), DELAY_MIN, DELAY_MAX);
		printerBlocksPerAction = clamp(parseInt(p.getProperty("printer.blocksPerAction"), printerBlocksPerAction), BLOCKS_MIN, BLOCKS_MAX);
		printerRange = Math.max(RANGE_MIN, Math.min(RANGE_MAX, parseDouble(p.getProperty("printer.range"), printerRange)));
		printerOnlyVisibleLayer = Boolean.parseBoolean(p.getProperty("printer.onlyVisibleLayer", String.valueOf(printerOnlyVisibleLayer)));
	}

	public static void save() {
		Properties p = new Properties();
		p.setProperty("printer.enabled", String.valueOf(printerEnabled));
		p.setProperty("printer.delay", String.valueOf(printerDelay));
		p.setProperty("printer.blocksPerAction", String.valueOf(printerBlocksPerAction));
		p.setProperty("printer.range", String.valueOf(printerRange));
		p.setProperty("printer.onlyVisibleLayer", String.valueOf(printerOnlyVisibleLayer));
		try {
			Files.createDirectories(file().getParent());
			try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
				p.store(w, "SchemaCraft");
			}
		} catch (IOException ignored) {
		}
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	private static int parseInt(String s, int def) {
		try {
			return s == null ? def : Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	private static double parseDouble(String s, double def) {
		try {
			return s == null ? def : Double.parseDouble(s.trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}
}
