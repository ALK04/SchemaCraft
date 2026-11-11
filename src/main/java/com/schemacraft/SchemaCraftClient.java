package com.schemacraft;

import com.mojang.blaze3d.platform.InputConstants;
import com.schemacraft.gui.SchemaScreen;
import com.schemacraft.paste.Paster;
import com.schemacraft.render.GhostRenderer;


import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.client.player.ClientHotbarScrollEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public final class SchemaCraftClient implements ClientModInitializer {
	public static final String MOD_ID = "schemacraft";

	/** Portée du clic quand on vise un bloc lointain (au-delà de la portée normale). */
	private static final double FAR_REACH = 200.0;

	public static final Item SELECTION_TOOL = Items.STICK;
	public static final Item PLACEMENT_TOOL = Items.WOODEN_SWORD;

	private static KeyMapping openMenuKey;
	private static KeyMapping toggleLayerKey;
	private static KeyMapping layerUpKey;
	private static KeyMapping layerDownKey;
	private static KeyMapping resourcesKey;
	private static KeyMapping undoPasteKey;

	@Override
	public void onInitializeClient() {
		Settings.load();
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		openMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.schemacraft.menu", InputConstants.Type.KEYBOARD, InputConstants.KEY_COMMA, category));
		toggleLayerKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.schemacraft.layer_mode", InputConstants.Type.KEYBOARD, InputConstants.KEY_EQUALS, category));
		layerUpKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.schemacraft.layer_up", InputConstants.Type.KEYBOARD, InputConstants.KEY_PAGEUP, category));
		layerDownKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.schemacraft.layer_down", InputConstants.Type.KEYBOARD, InputConstants.KEY_PAGEDOWN, category));
		// Touche ")" sur un clavier AZERTY (même position physique que "-" en QWERTY).
		resourcesKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.schemacraft.resources", InputConstants.Type.KEYBOARD, InputConstants.KEY_MINUS, category));
		// Avec Ctrl : Ctrl+Z sur un clavier AZERTY (même position physique que W en QWERTY).
		undoPasteKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.schemacraft.undo_paste", InputConstants.Type.KEYBOARD, InputConstants.KEY_W, category));

		ClientTickEvents.END_CLIENT_TICK.register(SchemaCraftClient::onTick);
		UseBlockCallback.EVENT.register(SchemaCraftClient::onUseBlock);
		UseItemCallback.EVENT.register(SchemaCraftClient::onUseItem);
		ClientHotbarScrollEvents.ALLOW.register((inventory, oldSlot, newSlot, scrollX, scrollY) -> !onHotbarScroll());
		LevelRenderEvents.COLLECT_SUBMITS.register(GhostRenderer::collectSubmits);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "info"), (g, delta) -> renderHud(g));
	}

	// ------------------------------------------------------------------ touches

	private static void onTick(Minecraft mc) {
		GhostRenderer.tick();
		if (mc.player == null) return;
		com.schemacraft.printer.Printer.tick(mc);
		Paster.tick(mc);

		while (openMenuKey.consumeClick()) {
			mc.setScreenAndShow(new SchemaScreen());
		}
		while (resourcesKey.consumeClick()) {
			mc.setScreenAndShow(SchemaScreen.openResources());
		}
		while (toggleLayerKey.consumeClick()) {
			SchemaState.toggleLayerMode();
			if (SchemaState.layerMode) {
				message(Component.literal("Affichage : couche par couche" + (SchemaState.isPlaced() ? " (Y=" + SchemaState.layer + ")" : "")).withStyle(ChatFormatting.YELLOW));
			} else {
				message(Component.literal("Affichage : tout le schéma").withStyle(ChatFormatting.AQUA));
			}
		}
		while (layerUpKey.consumeClick()) {
			if (SchemaState.layerMode) SchemaState.changeLayer(1);
		}
		while (layerDownKey.consumeClick()) {
			if (SchemaState.layerMode) SchemaState.changeLayer(-1);
		}
		while (undoPasteKey.consumeClick()) {
			// Seulement Ctrl + touche, épée en main : la même touche sert aussi à avancer.
			boolean ctrl = InputConstants.isKeyDown(InputConstants.KEY_LCONTROL) || InputConstants.isKeyDown(InputConstants.KEY_RCONTROL);
			if (ctrl && mc.player.getMainHandItem().getItem() == PLACEMENT_TOOL) Paster.undo();
		}
	}

	/** Shift + molette avec l'épée en bois : change de mode au lieu de changer d'objet. */
	private static boolean onHotbarScroll() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !mc.options.keyShift.isDown()) return false;
		if (mc.player.getMainHandItem().getItem() != PLACEMENT_TOOL) return false;
		SchemaState.pasteMode = !SchemaState.pasteMode;
		if (SchemaState.pasteMode) {
			String warn = Paster.allowed(mc.player) ? "" : "  (créatif + op requis)";
			message(Component.literal("Épée : mode Coller — clic droit = coller le schéma" + warn).withStyle(ChatFormatting.GOLD));
		} else {
			message(Component.literal("Épée : mode Placer — clic droit = poser l'hologramme").withStyle(ChatFormatting.AQUA));
		}
		return true;
	}

	// ------------------------------------------------------------------ clics

	private static InteractionResult onUseBlock(Player player, net.minecraft.world.level.Level level, InteractionHand hand, BlockHitResult hit) {
		if (!level.isClientSide() || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
		return handleClick(player, hit);
	}

	private static InteractionResult onUseItem(Player player, net.minecraft.world.level.Level level, InteractionHand hand) {
		if (!level.isClientSide() || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
		Item item = player.getMainHandItem().getItem();
		if (item != SELECTION_TOOL && item != PLACEMENT_TOOL) return InteractionResult.PASS;
		if (item == PLACEMENT_TOOL && SchemaState.loaded == null) return InteractionResult.PASS;
		// Mode Coller avec un hologramme déjà posé : pas besoin de viser un bloc.
		if (item == PLACEMENT_TOOL && SchemaState.pasteMode && SchemaState.isPlaced()) {
			Paster.paste();
			return InteractionResult.FAIL;
		}

		// On vise dans le vide (bloc hors de portée) : on cherche plus loin.
		HitResult far = player.pick(FAR_REACH, 1.0f, false);
		if (far instanceof BlockHitResult blockHit && far.getType() == HitResult.Type.BLOCK) {
			return handleClick(player, blockHit);
		}
		message(Component.literal("Aucun bloc visé").withStyle(ChatFormatting.GRAY));
		return InteractionResult.FAIL;
	}

	/**
	 * FAIL = on bloque l'action vanilla ET rien n'est envoyé au serveur
	 * (pas d'ouverture de coffre, de porte, etc.).
	 */
	private static InteractionResult handleClick(Player player, BlockHitResult hit) {
		Item item = player.getMainHandItem().getItem();
		if (item == SELECTION_TOOL) {
			BlockPos pos = hit.getBlockPos();
			if (player.isShiftKeyDown()) {
				SchemaState.pos2 = pos;
				message(Component.literal("Position 2 : " + fmt(pos) + sizeSuffix()).withStyle(ChatFormatting.BLUE));
			} else {
				SchemaState.pos1 = pos;
				message(Component.literal("Position 1 : " + fmt(pos) + sizeSuffix()).withStyle(ChatFormatting.RED));
			}
			return InteractionResult.FAIL;
		}
		if (item == PLACEMENT_TOOL && SchemaState.loaded != null && SchemaState.pasteMode) {
			// Colle là où est l'hologramme ; s'il n'est pas encore posé, on le pose d'abord sur la face cliquée.
			if (!SchemaState.isPlaced()) SchemaState.setOrigin(hit.getBlockPos().relative(hit.getDirection()));
			Paster.paste();
			return InteractionResult.FAIL;
		}
		if (item == PLACEMENT_TOOL && SchemaState.loaded != null) {
			BlockPos pos = hit.getBlockPos().relative(hit.getDirection());
			SchemaState.setOrigin(pos);
			message(Component.literal("Schéma « " + SchemaState.loaded.name() + " » posé en " + fmt(pos)).withStyle(ChatFormatting.AQUA));
			return InteractionResult.FAIL;
		}
		return InteractionResult.PASS;
	}

	private static String sizeSuffix() {
		if (!SchemaState.hasSelection()) return "";
		BlockPos a = SchemaState.pos1, b = SchemaState.pos2;
		int sx = Math.abs(a.getX() - b.getX()) + 1;
		int sy = Math.abs(a.getY() - b.getY()) + 1;
		int sz = Math.abs(a.getZ() - b.getZ()) + 1;
		return "  (" + sx + "x" + sy + "x" + sz + " = " + ((long) sx * sy * sz) + " blocs)";
	}

	// ------------------------------------------------------------------ HUD

	private static void renderHud(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		int x = 4;
		int y = 4;
		int line = mc.font.lineHeight + 2;

		Item held = mc.player.getMainHandItem().getItem();
		if (held == SELECTION_TOOL) {
			g.text(mc.font, "Sélection  P1: " + (SchemaState.pos1 == null ? "-" : fmt(SchemaState.pos1))
				+ "  P2: " + (SchemaState.pos2 == null ? "-" : fmt(SchemaState.pos2)) + sizeSuffix(), x, y, 0xFFFFFFFF, true);
			y += line;
		}
		if (held == PLACEMENT_TOOL) {
			g.text(mc.font, "Épée : " + (SchemaState.pasteMode ? "Coller" : "Placer") + "  [Shift + molette]",
				x, y, SchemaState.pasteMode ? 0xFFFFAA00 : 0xFF55FFFF, true);
			y += line;
		}
		String progress = Paster.progressText();
		if (progress != null) {
			g.text(mc.font, progress, x, y, 0xFF55FF55, true);
			y += line;
		}
		if (SchemaState.loaded != null) {
			String where = SchemaState.origin == null ? "non posé (épée en bois)" : fmt(SchemaState.origin);
			g.text(mc.font, "Schéma : " + SchemaState.loaded.name() + "  @ " + where, x, y, 0xFF55FFFF, true);
			y += line;
			if (SchemaState.isPlaced()) {
				if (SchemaState.layerMode) {
					g.text(mc.font, "Couche " + SchemaState.layerIndex() + "/" + SchemaState.loaded.sizeY()
						+ "  (Y=" + SchemaState.layer + ")  [PgUp/PgDn]", x, y, 0xFFFFD000, true);
					y += line;
				}
				if (Settings.printerEnabled) {
					g.text(mc.font, "Printer : actif", x, y, 0xFF55FF55, true);
					y += line;
				}
				int wrong = GhostRenderer.wrongBlockCount();
				if (wrong > 0) {
					g.text(mc.font, "Blocs incorrects : " + wrong, x, y, 0xFFFF5050, true);
				}
			}
		}
	}

	// ------------------------------------------------------------------ utilitaires

	public static String fmt(BlockPos p) {
		return p.getX() + " " + p.getY() + " " + p.getZ();
	}

	public static void message(Component text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) mc.player.sendOverlayMessage(text);
	}
}
