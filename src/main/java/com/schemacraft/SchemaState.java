package com.schemacraft;

import com.schemacraft.render.GhostRenderer;
import com.schemacraft.schematic.Schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

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
	/** Hologramme et cadre affichés (bouton Visible / Masqué). Masqué = printer en pause. */
	public static boolean visible = true;
	/** Couche affichée (Y absolu) en mode couche par couche. */
	public static int layer;

	public static boolean hasSelection() {
		return pos1 != null && pos2 != null;
	}

	public static boolean isPlaced() {
		return loaded != null && origin != null;
	}

	public static void setLoaded(Schematic schematic) {
		editHistory.clear();
		visible = true;
		base = schematic;
		loaded = schematic;
		rotation = Rotation.NONE;
		mirror = Mirror.NONE;
		origin = null;
		GhostRenderer.markDirty();
	}

	public static void unload() {
		editHistory.clear();
		visible = true;
		base = null;
		loaded = null;
		rotation = Rotation.NONE;
		mirror = Mirror.NONE;
		origin = null;
		GhostRenderer.markDirty();
	}

	// --- Rotation / miroir (comme Litematica) ---
	/** Schéma tel que sauvegardé ; "loaded" en est la version pivotée / miroir. */
	public static Schematic base;
	public static Rotation rotation = Rotation.NONE;
	public static Mirror mirror = Mirror.NONE;

	/** Pivote de 90° (sens horaire vu du dessus si clockwise). */
	public static void rotate(boolean clockwise) {
		rotation = rotation.getRotated(clockwise ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90);
		applyTransform();
	}

	/** Active / désactive un miroir (un seul à la fois, comme Litematica). */
	public static void toggleMirror(Mirror m) {
		mirror = mirror == m ? Mirror.NONE : m;
		applyTransform();
	}

	public static void resetTransform() {
		rotation = Rotation.NONE;
		mirror = Mirror.NONE;
		applyTransform();
	}

	private static void applyTransform() {
		if (base == null) return;
		loaded = base.transformed(rotation, mirror);
		clampLayer();
		GhostRenderer.markDirty();
	}

	// --- Édition (onglet Édition) ---
	private static final int MAX_UNDO = 10;
	/** Versions précédentes du schéma, la plus récente en tête. */
	private static final java.util.Deque<Schematic> editHistory = new java.util.ArrayDeque<>();

	/**
	 * Remplace le schéma par sa version modifiée (sans rotation ni miroir) en gardant
	 * placement, rotation et miroir : l'hologramme se met à jour tout de suite.
	 */
	public static void applyEdit(Schematic edited) {
		if (base == null) return;
		editHistory.push(base);
		while (editHistory.size() > MAX_UNDO) editHistory.removeLast();
		base = edited;
		applyTransform();
	}

	public static boolean canUndoEdit() {
		return !editHistory.isEmpty();
	}

	public static void undoEdit() {
		if (editHistory.isEmpty()) return;
		base = editHistory.pop();
		applyTransform();
	}

	public static String rotationText() {
		return switch (rotation) {
			case NONE -> "0°";
			case CLOCKWISE_90 -> "90°";
			case CLOCKWISE_180 -> "180°";
			case COUNTERCLOCKWISE_90 -> "270°";
		};
	}

	public static String mirrorText() {
		return switch (mirror) {
			case NONE -> "aucun";
			case LEFT_RIGHT -> "Z";
			case FRONT_BACK -> "X";
		};
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
