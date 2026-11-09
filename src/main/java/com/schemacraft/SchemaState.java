package com.schemacraft;

import com.schemacraft.render.GhostRenderer;
import com.schemacraft.schematic.Schematic;

import net.minecraft.core.BlockPos;

/** État global du mod (côté client uniquement). */
public final class SchemaState {
	private SchemaState() {}

	// --- Sélection (bâton) ---
	public static BlockPos pos1;
	public static BlockPos pos2;

	// --- Placement (épée en bois) ---
	public static Schematic loaded;
	/** Coin minimum du schéma placé dans le monde, null = pas encore posé. */
	public static BlockPos origin;

	// --- Affichage ---
	public static boolean layerMode;
	/** Couche affichée (Y absolu) en mode couche par couche. */
	public static int layer;

	public static boolean hasSelection() {
		return pos1 != null && pos2 != null;
	}

	public static boolean isPlaced() {
		return loaded != null && origin != null;
	}

	public static void setLoaded(Schematic schematic) {
		loaded = schematic;
		origin = null;
		GhostRenderer.markDirty();
	}

	public static void unload() {
		loaded = null;
		origin = null;
		GhostRenderer.markDirty();
	}

	public static void setOrigin(BlockPos pos) {
		origin = pos;
		clampLayer();
		GhostRenderer.markDirty();
	}

	public static void moveOrigin(int dx, int dy, int dz) {
		if (origin == null) return;
		int oldBottom = origin.getY();
		origin = origin.offset(dx, dy, dz);
		// En mode couche, on garde la même couche relative au schéma.
		layer += origin.getY() - oldBottom;
		clampLayer();
		GhostRenderer.markDirty();
	}

	public static void toggleLayerMode() {
		layerMode = !layerMode;
		if (layerMode && isPlaced()) {
			if (layer < origin.getY() || layer >= origin.getY() + loaded.sizeY()) {
				layer = origin.getY();
			}
		}
		GhostRenderer.markDirty();
	}

	public static void changeLayer(int delta) {
		layer += delta;
		clampLayer();
		GhostRenderer.markDirty();
	}

	private static void clampLayer() {
		if (!isPlaced()) return;
		int min = origin.getY();
		int max = origin.getY() + loaded.sizeY() - 1;
		if (layer < min) layer = min;
		if (layer > max) layer = max;
	}

	/** Index (1-based) de la couche courante dans le schéma. */
	public static int layerIndex() {
		return isPlaced() ? layer - origin.getY() + 1 : 0;
	}
}
