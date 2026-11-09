package com.schemacraft.render;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.schemacraft.SchemaState;
import com.schemacraft.schematic.Schematic;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Dessine :
 *  - la sélection du bâton (pos1 rouge, pos2 bleu, zone blanche),
 *  - l'hologramme du schéma placé (blocs semi-transparents),
 *  - les blocs incorrects (cadre rouge) et les blocs sans modèle (cadre bleu clair).
 *
 * La géométrie de l'hologramme est recalculée seulement quand quelque chose change
 * (déplacement, couche...) et une fois par seconde pour suivre ce que le joueur construit.
 */
public final class GhostRenderer {
	private GhostRenderer() {}

	private static final float ALPHA = 0.6f;
	private static final int FULL_BRIGHT = 0xF000F0;
	private static final int MAX_VERTICES = 4_000_000;
	private static final int MAX_BOXES = 20_000;

	private static final int COLOR_POS1 = 0xFFFF4040;
	private static final int COLOR_POS2 = 0xFF4080FF;
	private static final int COLOR_AREA = 0xFFFFFFFF;
	private static final int COLOR_PLACEMENT = 0xFF00E5FF;
	private static final int COLOR_LAYER = 0xFFFFD000;
	private static final int COLOR_WRONG = 0xFFFF2020;
	private static final int COLOR_PLACEHOLDER = 0xFF60C8FF;

	private static final Direction[] DIRECTIONS = Direction.values();

	// Maillage en cache, coordonnées locales au coin du schéma.
	private static float[] positions = new float[0]; // x,y,z
	private static float[] uvs = new float[0];       // u,v
	private static float[] normals = new float[0];   // nx,ny,nz
	private static int[] colors = new int[0];
	private static int vertexCount;

	private static int[] wrongBoxes = new int[0];       // x,y,z locaux
	private static int wrongCount;
	private static int[] placeholderBoxes = new int[0]; // x,y,z locaux
	private static int placeholderCount;

	private static boolean dirty = true;
	private static int tickCounter;

	public static void markDirty() {
		dirty = true;
	}

	/** Appelé à chaque tick client. */
	public static void tick() {
		if (++tickCounter >= 20) {
			tickCounter = 0;
			dirty = true;
		}
	}

	public static int wrongBlockCount() {
		return wrongCount;
	}

	// ------------------------------------------------------------------ rendu

	public static void collectSubmits(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;

		Vec3 cam = ctx.levelState().cameraRenderState.pos;
		PoseStack poseStack = ctx.poseStack();
		SubmitNodeCollector out = ctx.submitNodeCollector();
		float lineWidth = ctx.gameRenderer().gameRenderState().windowRenderState.appropriateLineWidth;

		// --- Hologramme ---
		if (SchemaState.isPlaced()) {
			if (dirty) rebuild(mc.level);

			BlockPos o = SchemaState.origin;
			final float ox = (float) (o.getX() - cam.x);
			final float oy = (float) (o.getY() - cam.y);
			final float oz = (float) (o.getZ() - cam.z);
			final float[] pos = positions;
			final float[] uv = uvs;
			final float[] nor = normals;
			final int[] col = colors;
			final int count = vertexCount;
			if (count > 0) {
				out.submitCustomGeometry(poseStack, RenderTypes.translucentMovingBlock(), (pose, vc) -> {
					Matrix4f m = pose.pose();
					Vector3f p = new Vector3f();
					Vector3f n = new Vector3f();
					for (int i = 0; i < count; i++) {
						m.transformPosition(pos[i * 3] + ox, pos[i * 3 + 1] + oy, pos[i * 3 + 2] + oz, p);
						pose.transformNormal(nor[i * 3], nor[i * 3 + 1], nor[i * 3 + 2], n);
						vc.addVertex(p.x, p.y, p.z, col[i], uv[i * 2], uv[i * 2 + 1],
							OverlayTexture.NO_OVERLAY, FULL_BRIGHT, n.x, n.y, n.z);
					}
				});
			}
		}

		// --- Lignes (sélection, cadre du schéma, erreurs) ---
		final boolean hasLines = SchemaState.pos1 != null || SchemaState.pos2 != null || SchemaState.isPlaced();
		if (!hasLines) return;

		final BlockPos p1 = SchemaState.pos1;
		final BlockPos p2 = SchemaState.pos2;
		final Schematic schem = SchemaState.loaded;
		final BlockPos origin = SchemaState.origin;
		final boolean layerMode = SchemaState.layerMode;
		final int layer = SchemaState.layer;
		final int[] wrong = wrongBoxes;
		final int nWrong = wrongCount;
		final int[] holders = placeholderBoxes;
		final int nHolders = placeholderCount;

		out.submitCustomGeometry(poseStack, RenderTypes.lines(), (pose, vc) -> {
			double cx = cam.x, cy = cam.y, cz = cam.z;
			if (p1 != null && p2 != null) {
				box(vc, pose, Math.min(p1.getX(), p2.getX()) - cx, Math.min(p1.getY(), p2.getY()) - cy, Math.min(p1.getZ(), p2.getZ()) - cz,
					Math.max(p1.getX(), p2.getX()) + 1 - cx, Math.max(p1.getY(), p2.getY()) + 1 - cy, Math.max(p1.getZ(), p2.getZ()) + 1 - cz,
					COLOR_AREA, lineWidth);
			}
			if (p1 != null) blockBox(vc, pose, p1, cx, cy, cz, 0.02, COLOR_POS1, lineWidth * 1.5f);
			if (p2 != null) blockBox(vc, pose, p2, cx, cy, cz, 0.02, COLOR_POS2, lineWidth * 1.5f);

			if (schem != null && origin != null) {
				double x0 = origin.getX() - cx, y0 = origin.getY() - cy, z0 = origin.getZ() - cz;
				box(vc, pose, x0, y0, z0, x0 + schem.sizeX(), y0 + schem.sizeY(), z0 + schem.sizeZ(), COLOR_PLACEMENT, lineWidth);
				if (layerMode) {
					double ly = layer - cy;
					box(vc, pose, x0, ly, z0, x0 + schem.sizeX(), ly + 1, z0 + schem.sizeZ(), COLOR_LAYER, lineWidth);
				}
				for (int i = 0; i < nWrong; i++) {
					double bx = x0 + wrong[i * 3], by = y0 + wrong[i * 3 + 1], bz = z0 + wrong[i * 3 + 2];
					box(vc, pose, bx - 0.01, by - 0.01, bz - 0.01, bx + 1.01, by + 1.01, bz + 1.01, COLOR_WRONG, lineWidth);
				}
				for (int i = 0; i < nHolders; i++) {
					double bx = x0 + holders[i * 3], by = y0 + holders[i * 3 + 1], bz = z0 + holders[i * 3 + 2];
					box(vc, pose, bx + 0.1, by + 0.1, bz + 0.1, bx + 0.9, by + 0.9, bz + 0.9, COLOR_PLACEHOLDER, lineWidth);
				}
			}
		});
	}

	private static void blockBox(VertexConsumer vc, PoseStack.Pose pose, BlockPos p, double cx, double cy, double cz,
			double grow, int color, float width) {
		box(vc, pose, p.getX() - cx - grow, p.getY() - cy - grow, p.getZ() - cz - grow,
			p.getX() + 1 - cx + grow, p.getY() + 1 - cy + grow, p.getZ() + 1 - cz + grow, color, width);
	}

	private static void box(VertexConsumer vc, PoseStack.Pose pose, double x0, double y0, double z0,
			double x1, double y1, double z1, int color, float width) {
		float a = (float) x0, b = (float) y0, c = (float) z0;
		float d = (float) x1, e = (float) y1, f = (float) z1;
		// arêtes le long de X
		line(vc, pose, a, b, c, d, b, c, color, width);
		line(vc, pose, a, e, c, d, e, c, color, width);
		line(vc, pose, a, b, f, d, b, f, color, width);
		line(vc, pose, a, e, f, d, e, f, color, width);
		// arêtes le long de Y
		line(vc, pose, a, b, c, a, e, c, color, width);
		line(vc, pose, d, b, c, d, e, c, color, width);
		line(vc, pose, a, b, f, a, e, f, color, width);
		line(vc, pose, d, b, f, d, e, f, color, width);
		// arêtes le long de Z
		line(vc, pose, a, b, c, a, b, f, color, width);
		line(vc, pose, d, b, c, d, b, f, color, width);
		line(vc, pose, a, e, c, a, e, f, color, width);
		line(vc, pose, d, e, c, d, e, f, color, width);
	}

	private static void line(VertexConsumer vc, PoseStack.Pose pose, float x0, float y0, float z0,
			float x1, float y1, float z1, int color, float width) {
		float nx = x1 - x0, ny = y1 - y0, nz = z1 - z0;
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len > 0) {
			nx /= len;
			ny /= len;
			nz /= len;
		}
		vc.addVertex(pose, x0, y0, z0).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
		vc.addVertex(pose, x1, y1, z1).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
	}

	// ------------------------------------------------------- construction du maillage

	private static void rebuild(ClientLevel level) {
		dirty = false;
		vertexCount = 0;
		wrongCount = 0;
		placeholderCount = 0;

		Schematic s = SchemaState.loaded;
		BlockPos o = SchemaState.origin;
		if (s == null || o == null) return;

		int yMin = 0;
		int yMax = s.sizeY() - 1;
		if (SchemaState.layerMode) {
			yMin = yMax = SchemaState.layer - o.getY();
			if (yMin < 0 || yMin >= s.sizeY()) return;
		}

		Minecraft mc = Minecraft.getInstance();
		BlockStateModelSet models = mc.getModelManager().getBlockStateModelSet();
		BlockColors blockColors = mc.getBlockColors();
		List<BlockStateModelPart> parts = new ArrayList<>();
		RandomSource random = RandomSource.create();
		BlockPos.MutableBlockPos wp = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos np = new BlockPos.MutableBlockPos();

		for (int y = yMin; y <= yMax; y++) {
			for (int z = 0; z < s.sizeZ(); z++) {
				for (int x = 0; x < s.sizeX(); x++) {
					BlockState state = s.get(x, y, z);
					if (state.isAir()) continue;

					wp.set(o.getX() + x, o.getY() + y, o.getZ() + z);
					BlockState world = level.getBlockState(wp);
					if (world == state) continue; // déjà construit correctement
					if (!world.isAir() && !world.canBeReplaced()) {
						addBox(true, x, y, z);
						continue;
					}

					int before = vertexCount;
					parts.clear();
					random.setSeed(state.getSeed(wp));
					try {
						models.get(state).collectParts(random, parts);
					} catch (RuntimeException ignored) {
						parts.clear();
					}
					for (BlockStateModelPart part : parts) {
						for (Direction dir : DIRECTIONS) {
							if (isCulled(s, level, x, y, z, dir, yMin, yMax, wp, np)) continue;
							for (BakedQuad quad : part.getQuads(dir)) {
								addQuad(quad, x, y, z, state, level, wp, blockColors);
							}
						}
						for (BakedQuad quad : part.getQuads(null)) {
							addQuad(quad, x, y, z, state, level, wp, blockColors);
						}
					}
					if (vertexCount == before) {
						addBox(false, x, y, z); // coffre, panneau, liquide... : pas de modèle simple
					}
					if (vertexCount >= MAX_VERTICES) return;
				}
			}
		}
	}

	private static boolean isCulled(Schematic s, ClientLevel level, int x, int y, int z, Direction dir,
			int yMin, int yMax, BlockPos.MutableBlockPos wp, BlockPos.MutableBlockPos np) {
		int nx = x + dir.getStepX();
		int ny = y + dir.getStepY();
		int nz = z + dir.getStepZ();
		boolean inside = nx >= 0 && nz >= 0 && nx < s.sizeX() && nz < s.sizeZ() && ny >= yMin && ny <= yMax;
		if (inside && s.get(nx, ny, nz).isSolidRender()) return true;
		np.set(wp.getX() + dir.getStepX(), wp.getY() + dir.getStepY(), wp.getZ() + dir.getStepZ());
		return level.getBlockState(np).isSolidRender();
	}

	private static void addQuad(BakedQuad quad, int x, int y, int z, BlockState state, ClientLevel level,
			BlockPos wp, BlockColors blockColors) {
		ensureVertexCapacity(vertexCount + 4);

		int tint = 0xFFFFFF;
		BakedQuad.MaterialInfo info = quad.materialInfo();
		if (info.isTinted()) {
			try {
				BlockTintSource source = blockColors.getTintSource(state, info.tintIndex());
				if (source != null) tint = source.colorInWorld(state, level, wp);
			} catch (RuntimeException ignored) {
			}
		}
		float shade = shade(quad.direction());
		int r = (int) (((tint >> 16) & 0xFF) * shade);
		int g = (int) (((tint >> 8) & 0xFF) * shade);
		int b = (int) ((tint & 0xFF) * shade);
		int color = ((int) (ALPHA * 255) << 24) | (r << 16) | (g << 8) | b;

		Vector3fc normal = quad.direction().getUnitVec3f();
		for (int i = 0; i < 4; i++) {
			Vector3fc p = quad.position(i);
			long packed = quad.packedUV(i);
			int v = vertexCount++;
			positions[v * 3] = x + p.x();
			positions[v * 3 + 1] = y + p.y();
			positions[v * 3 + 2] = z + p.z();
			uvs[v * 2] = UVPair.unpackU(packed);
			uvs[v * 2 + 1] = UVPair.unpackV(packed);
			normals[v * 3] = normal.x();
			normals[v * 3 + 1] = normal.y();
			normals[v * 3 + 2] = normal.z();
			colors[v] = color;
		}
	}

	private static float shade(Direction dir) {
		return switch (dir) {
			case DOWN -> 0.5f;
			case UP -> 1.0f;
			case NORTH, SOUTH -> 0.8f;
			case WEST, EAST -> 0.6f;
		};
	}

	private static void ensureVertexCapacity(int needed) {
		if (colors.length >= needed) return;
		int cap = Math.max(needed, Math.max(1024, colors.length * 2));
		positions = Arrays.copyOf(positions, cap * 3);
		uvs = Arrays.copyOf(uvs, cap * 2);
		normals = Arrays.copyOf(normals, cap * 3);
		colors = Arrays.copyOf(colors, cap);
	}

	private static void addBox(boolean wrong, int x, int y, int z) {
		if (wrong) {
			if (wrongCount >= MAX_BOXES) return;
			if (wrongBoxes.length < (wrongCount + 1) * 3) wrongBoxes = Arrays.copyOf(wrongBoxes, Math.max(96, wrongBoxes.length * 2));
			wrongBoxes[wrongCount * 3] = x;
			wrongBoxes[wrongCount * 3 + 1] = y;
			wrongBoxes[wrongCount * 3 + 2] = z;
			wrongCount++;
		} else {
			if (placeholderCount >= MAX_BOXES) return;
			if (placeholderBoxes.length < (placeholderCount + 1) * 3) placeholderBoxes = Arrays.copyOf(placeholderBoxes, Math.max(96, placeholderBoxes.length * 2));
			placeholderBoxes[placeholderCount * 3] = x;
			placeholderBoxes[placeholderCount * 3 + 1] = y;
			placeholderBoxes[placeholderCount * 3 + 2] = z;
			placeholderCount++;
		}
	}
}
