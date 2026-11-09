package com.schemacraft.printer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.schemacraft.SchemaState;
import com.schemacraft.Settings;
import com.schemacraft.render.GhostRenderer;
import com.schemacraft.schematic.Schematic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Printer inspiré de litematica-printer : pose automatiquement les blocs manquants
 * du schéma qui sont à portée du joueur.
 *
 * Pour trouver comment poser un bloc dans le bon état (orientation, demi-dalle, axe...),
 * on simule le placement vanilla (BlockItem#getPlacementState) avec plusieurs faces
 * cliquées, hauteurs de clic et rotations du joueur, et on garde la combinaison
 * qui donne l'état le plus proche de celui du schéma. Si une rotation est nécessaire,
 * on l'envoie au serveur juste avant le clic puis on remet la vraie rotation.
 */
public final class Printer {
	private Printer() {}

	private static final Direction[] FACES = {Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
	private static final double[] HIT_Y = {0.25, 0.75};
	private static final float[] YAWS = {0f, 90f, 180f, -90f};
	private static final float[] PITCHES = {0f, 80f, -80f};

	/** Ticks d'attente avant de retenter une position (bloc impossible / rejeté). */
	private static final int RETRY_PLACED = 20;
	private static final int RETRY_NO_ITEM = 40;
	private static final int RETRY_IMPOSSIBLE = 100;

	private static final Map<Long, Integer> retryAt = new HashMap<>();
	private static int tickCount;
	private static int cooldown;

	private static Method placementStateMethod;
	private static boolean reflectionTried;

	/** Plan de placement trouvé par simulation. rotate=false = pas besoin de tourner. */
	private record Plan(BlockHitResult hit, boolean rotate, float yaw, float pitch) {}

	private record Candidate(BlockPos pos, BlockState state, double dist) {}

	public static void tick(Minecraft mc) {
		tickCount++;
		if (!Settings.printerEnabled || !SchemaState.isPlaced()) return;
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null || mc.gameMode == null || mc.gui.screen() != null) return;
		if (player.isSpectator()) return;
		if (cooldown > 0) {
			cooldown--;
			return;
		}
		if (tickCount % 200 == 0) retryAt.values().removeIf(t -> t < tickCount);

		int placed = 0;
		for (Candidate c : collectCandidates(player, level)) {
			if (tryPlace(mc, player, level, c.pos(), c.state())) {
				placed++;
				if (placed >= Settings.printerBlocksPerAction) break;
			}
		}
		if (placed > 0) {
			cooldown = Settings.printerDelay;
			GhostRenderer.markDirty();
		}
	}

	// ------------------------------------------------------------- recherche des blocs

	private static List<Candidate> collectCandidates(LocalPlayer player, ClientLevel level) {
		Schematic s = SchemaState.loaded;
		BlockPos o = SchemaState.origin;
		Vec3 eye = player.getEyePosition();
		double r = Settings.printerRange;
		double r2 = r * r;

		int minX = Math.max(o.getX(), (int) Math.floor(eye.x - r));
		int maxX = Math.min(o.getX() + s.sizeX() - 1, (int) Math.floor(eye.x + r));
		int minY = Math.max(o.getY(), (int) Math.floor(eye.y - r));
		int maxY = Math.min(o.getY() + s.sizeY() - 1, (int) Math.floor(eye.y + r));
		int minZ = Math.max(o.getZ(), (int) Math.floor(eye.z - r));
		int maxZ = Math.min(o.getZ() + s.sizeZ() - 1, (int) Math.floor(eye.z + r));
		if (SchemaState.layerMode && Settings.printerOnlyVisibleLayer) {
			minY = Math.max(minY, SchemaState.layer);
			maxY = Math.min(maxY, SchemaState.layer);
		}

		List<Candidate> out = new ArrayList<>();
		AABB playerBox = player.getBoundingBox();
		for (int y = minY; y <= maxY; y++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int x = minX; x <= maxX; x++) {
					double dx = x + 0.5 - eye.x, dy = y + 0.5 - eye.y, dz = z + 0.5 - eye.z;
					double d2 = dx * dx + dy * dy + dz * dz;
					if (d2 > r2) continue;

					BlockState want = s.get(x - o.getX(), y - o.getY(), z - o.getZ());
					if (want.isAir() || isSecondaryPart(want)) continue;

					BlockPos pos = new BlockPos(x, y, z);
					Integer retry = retryAt.get(pos.asLong());
					if (retry != null && retry > tickCount) continue;

					BlockState world = level.getBlockState(pos);
					if (world == want) continue;
					if (!world.canBeReplaced() && !canCompleteSlab(world, want)) continue;
					if (!want.getCollisionShape(level, pos).isEmpty() && playerBox.intersects(new AABB(pos))) continue;

					out.add(new Candidate(pos, want, d2));
				}
			}
		}
		// Du bas vers le haut (les blocs ont besoin de support), puis du plus proche au plus loin.
		out.sort(Comparator.comparingInt((Candidate c) -> c.pos().getY()).thenComparingDouble(Candidate::dist));
		return out;
	}

	/** Moitié haute d'une porte / plante haute, tête de lit : posée automatiquement avec l'autre moitié. */
	private static boolean isSecondaryPart(BlockState state) {
		if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
			&& state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return true;
		return state.hasProperty(BlockStateProperties.BED_PART)
			&& state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD;
	}

	/** Une demi-dalle déjà posée qui doit devenir une dalle double. */
	private static boolean canCompleteSlab(BlockState world, BlockState want) {
		return world.getBlock() == want.getBlock()
			&& want.hasProperty(BlockStateProperties.SLAB_TYPE)
			&& want.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE
			&& world.getValue(BlockStateProperties.SLAB_TYPE) != SlabType.DOUBLE;
	}

	// ------------------------------------------------------------- placement

	private static boolean tryPlace(Minecraft mc, LocalPlayer player, ClientLevel level, BlockPos pos, BlockState want) {
		Item item = want.getBlock().asItem();
		if (item == Items.AIR || !(item instanceof BlockItem)) {
			retryAt.put(pos.asLong(), tickCount + RETRY_IMPOSSIBLE);
			return false;
		}

		int slot = ensureInHotbar(mc, player, item);
		if (slot < 0) {
			retryAt.put(pos.asLong(), tickCount + RETRY_NO_ITEM);
			return false;
		}
		ItemStack stack = player.getInventory().getItem(slot);
		if (stack.getItem() != item) {
			// Échange d'inventaire envoyé, l'objet arrive au prochain tick.
			return false;
		}

		Plan plan = findPlan(player, level, pos, want, stack);
		if (plan == null) {
			retryAt.put(pos.asLong(), tickCount + RETRY_IMPOSSIBLE);
			return false;
		}

		player.getInventory().setSelectedSlot(slot);

		float realYaw = player.getYRot();
		float realPitch = player.getXRot();
		float realYawO = player.yRotO;
		float realPitchO = player.xRotO;
		InteractionResult result;
		if (plan.rotate()) {
			mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(plan.yaw(), plan.pitch(), player.onGround(), player.horizontalCollision));
			setRotation(player, plan.yaw(), plan.pitch());
		}
		try {
			result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, plan.hit());
		} finally {
			if (plan.rotate()) {
				player.setYRot(realYaw);
				player.setXRot(realPitch);
				player.yRotO = realYawO;
				player.xRotO = realPitchO;
				mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(realYaw, realPitch, player.onGround(), player.horizontalCollision));
			}
		}

		retryAt.put(pos.asLong(), tickCount + RETRY_PLACED);
		if (result.consumesAction()) {
			player.swing(InteractionHand.MAIN_HAND, stack.getInteractAnimation(), true);
			return true;
		}
		return false;
	}

	/**
	 * Met l'objet dans la barre rapide si besoin et renvoie son slot (0-8), ou -1 si le joueur ne l'a pas.
	 * En créatif, l'objet est créé directement.
	 */
	private static int ensureInHotbar(Minecraft mc, LocalPlayer player, Item item) {
		Inventory inv = player.getInventory();
		for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
			if (inv.getItem(i).getItem() == item) return i;
		}
		if (player.hasInfiniteMaterials()) {
			int slot = inv.getSuitableHotbarSlot();
			ItemStack stack = new ItemStack(item);
			inv.setItem(slot, stack);
			mc.gameMode.handleCreativeModeItemAdd(stack, 36 + slot);
			return slot;
		}
		for (int i = Inventory.SELECTION_SIZE; i < 36; i++) {
			if (inv.getItem(i).getItem() == item) {
				int target = inv.getSuitableHotbarSlot();
				// Dans l'InventoryMenu, les slots 9..35 de l'inventaire ont le même index.
				mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, i, target, ContainerInput.SWAP, player);
				return target;
			}
		}
		return -1;
	}

	// ------------------------------------------------------------- simulation

	private static Plan findPlan(LocalPlayer player, ClientLevel level, BlockPos pos, BlockState want, ItemStack stack) {
		BlockState world = level.getBlockState(pos);
		if (canCompleteSlab(world, want)) {
			boolean bottom = world.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.BOTTOM;
			Direction face = bottom ? Direction.UP : Direction.DOWN;
			Vec3 loc = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
			return new Plan(new BlockHitResult(loc, face, pos, false), false, 0, 0);
		}

		BlockItem blockItem = (BlockItem) stack.getItem();
		int total = want.getProperties().size();

		float yaw0 = player.getYRot(), pitch0 = player.getXRot();
		float yawO = player.yRotO, pitchO = player.xRotO;
		Plan best = null;
		int bestScore = -1;
		try {
			// 1) Sans tourner le joueur (aucun paquet de rotation à envoyer).
			for (Direction face : FACES) {
				for (double hy : hitHeights(face)) {
					BlockHitResult hit = hitFor(pos, face, hy);
					int score = score(player, blockItem, stack, hit, want);
					if (score > bestScore) {
						bestScore = score;
						best = new Plan(hit, false, 0, 0);
						if (score == total) return best;
					}
				}
			}
			// 2) Avec une rotation (escaliers, fours, pistons, observateurs...).
			for (float yaw : YAWS) {
				for (float pitch : PITCHES) {
					setRotation(player, yaw, pitch);
					for (Direction face : FACES) {
						for (double hy : hitHeights(face)) {
							BlockHitResult hit = hitFor(pos, face, hy);
							int score = score(player, blockItem, stack, hit, want);
							if (score > bestScore) {
								bestScore = score;
								best = new Plan(hit, true, yaw, pitch);
								if (score == total) return best;
							}
						}
					}
				}
			}
		} finally {
			player.setYRot(yaw0);
			player.setXRot(pitch0);
			player.yRotO = yawO;
			player.xRotO = pitchO;
		}
		return best;
	}

	private static double[] hitHeights(Direction face) {
		return face.getAxis() == Direction.Axis.Y ? new double[] {0.5} : HIT_Y;
	}

	private static BlockHitResult hitFor(BlockPos pos, Direction face, double hy) {
		double x = pos.getX() + 0.5 + face.getStepX() * 0.5;
		double z = pos.getZ() + 0.5 + face.getStepZ() * 0.5;
		double y = face == Direction.UP ? pos.getY() + 1.0 : face == Direction.DOWN ? pos.getY() : pos.getY() + hy;
		return new BlockHitResult(new Vec3(x, y, z), face, pos, false);
	}

	/** -1 = impossible / mauvais bloc, sinon nombre de propriétés identiques au schéma. */
	private static int score(LocalPlayer player, BlockItem item, ItemStack stack, BlockHitResult hit, BlockState want) {
		BlockState result;
		try {
			BlockPlaceContext ctx = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit);
			if (!ctx.canPlace()) return -1;
			BlockPlaceContext updated = item.updatePlacementContext(ctx);
			if (updated == null) return -1;
			result = placementState(item, updated);
		} catch (RuntimeException e) {
			return -1;
		}
		if (result == null || result.getBlock() != want.getBlock()) return -1;
		int score = 0;
		for (Property<?> p : want.getProperties()) {
			if (result.hasProperty(p) && result.getValue(p).equals(want.getValue(p))) score++;
		}
		return score;
	}

	/** BlockItem#getPlacementState est protégée : on y accède par réflexion (gère torches murales, etc.). */
	private static BlockState placementState(BlockItem item, BlockPlaceContext ctx) {
		if (!reflectionTried) {
			reflectionTried = true;
			try {
				placementStateMethod = BlockItem.class.getDeclaredMethod("getPlacementState", BlockPlaceContext.class);
				placementStateMethod.setAccessible(true);
			} catch (ReflectiveOperationException | RuntimeException e) {
				placementStateMethod = null;
			}
		}
		if (placementStateMethod != null) {
			try {
				return (BlockState) placementStateMethod.invoke(item, ctx);
			} catch (ReflectiveOperationException e) {
				// on retombe sur la méthode du bloc
			}
		}
		return item.getBlock().getStateForPlacement(ctx);
	}

	private static void setRotation(LocalPlayer player, float yaw, float pitch) {
		player.setYRot(yaw);
		player.setXRot(pitch);
		player.yRotO = yaw;
		player.xRotO = pitch;
	}
}
