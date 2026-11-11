package com.schemacraft.paste;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.schemacraft.SchemaCraftClient;
import com.schemacraft.SchemaState;
import com.schemacraft.Settings;
import com.schemacraft.schematic.Schematic;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Collage instantané du schéma placé (épée en bois, mode Coller), réservé au créatif + droits de commande.
 * <ul>
 * <li>Solo : écrit directement dans le monde du serveur intégré, sans mises à jour de blocs.</li>
 * <li>Serveur : envoie des commandes {@code /setblock} et {@code /fill} en mode {@code strict}
 * (les blocs identiques côte à côte sont regroupés), un paquet limité par tick.</li>
 * </ul>
 * Chaque collage garde les anciens blocs pour pouvoir l'annuler (Ctrl+Z, 10 niveaux).
 */
public final class Paster {
	private Paster() {}

	private record Op(BlockPos pos, BlockState state) {}

	private static final class Job {
		final List<Op> ops;
		final boolean undo;
		final ResourceKey<Level> dimension;
		int next;
		boolean feedbackDisabled;

		Job(List<Op> ops, boolean undo, ResourceKey<Level> dimension) {
			this.ops = ops;
			this.undo = undo;
			this.dimension = dimension;
		}
	}

	private static final int MAX_HISTORY = 10;
	/** Blocs écrits par tick en solo. */
	private static final int SOLO_PER_TICK = 16_384;
	/** Commandes envoyées par tick sur un serveur. */
	private static final int COMMANDS_PER_TICK = 64;
	private static final int SKIP_UPDATES = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

	/** Anciens blocs de chaque collage, le plus récent en tête. */
	private static final Deque<List<Op>> history = new ArrayDeque<>();
	private static Job current;

	// ------------------------------------------------------------------ autorisation

	/** Créatif + droits de commande (op niveau 2, ou triche activée en solo). */
	public static boolean allowed(LocalPlayer player) {
		return player.isCreative() && player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	private static boolean checkAllowed(LocalPlayer player) {
		if (allowed(player)) return true;
		SchemaCraftClient.message(Component.literal(player.isCreative()
			? "Coller : il faut les droits de commande (op / triche activée)"
			: "Coller : seulement en mode créatif").withStyle(ChatFormatting.RED));
		return false;
	}

	public static boolean busy() {
		return current != null;
	}

	public static boolean canUndo() {
		return !history.isEmpty();
	}

	/** "Collage : 1200 / 2800" pendant un collage, sinon null. */
	public static String progressText() {
		if (current == null) return null;
		return (current.undo ? "Annulation : " : "Collage : ") + current.next + " / " + current.ops.size();
	}

	// ------------------------------------------------------------------ actions

	/** Colle le schéma chargé à son emplacement actuel (rotation / miroir compris). */
	public static void paste() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || !SchemaState.isPlaced()) return;
		if (!checkAllowed(player)) return;
		if (current != null) {
			SchemaCraftClient.message(Component.literal("Un collage est déjà en cours").withStyle(ChatFormatting.GRAY));
			return;
		}

		Schematic s = SchemaState.loaded;
		BlockPos o = SchemaState.origin;
		boolean replaceAir = Settings.pasteReplaceAir;
		List<Op> ops = new ArrayList<>();
		List<Op> old = new ArrayList<>();
		for (int y = 0; y < s.sizeY(); y++) {
			for (int z = 0; z < s.sizeZ(); z++) {
				for (int x = 0; x < s.sizeX(); x++) {
					BlockState state = s.get(x, y, z);
					if (state.isAir() && !replaceAir) continue;
					BlockPos pos = o.offset(x, y, z);
					if (mc.level.isOutsideBuildHeight(pos)) continue;
					BlockState before = mc.level.getBlockState(pos);
					if (before == state) continue;
					ops.add(new Op(pos, state));
					old.add(new Op(pos, before));
				}
			}
		}
		if (ops.isEmpty()) {
			SchemaCraftClient.message(Component.literal("Rien à coller : tout est déjà en place").withStyle(ChatFormatting.GRAY));
			return;
		}
		history.push(old);
		while (history.size() > MAX_HISTORY) history.removeLast();
		current = new Job(ops, false, mc.level.dimension());
	}

	/** Annule le dernier collage (et arrête celui en cours s'il y en a un). */
	public static void undo() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) return;
		if (history.isEmpty()) {
			SchemaCraftClient.message(Component.literal("Aucun collage à annuler").withStyle(ChatFormatting.GRAY));
			return;
		}
		if (!checkAllowed(player)) return;
		if (current != null && current.undo) return; // une annulation est déjà en cours
		Job interrupted = current;
		current = null;
		if (interrupted != null) restoreFeedback(interrupted);
		current = new Job(history.pop(), true, mc.level.dimension());
	}

	// ------------------------------------------------------------------ exécution

	public static void tick(Minecraft mc) {
		Job job = current;
		if (job == null) return;
		if (mc.player == null || mc.level == null || mc.getConnection() == null || mc.level.dimension() != job.dimension) {
			current = null; // déconnexion ou changement de dimension : on abandonne
			return;
		}

		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			int from = job.next;
			int to = Math.min(job.ops.size(), from + SOLO_PER_TICK);
			List<Op> batch = job.ops.subList(from, to);
			server.execute(() -> {
				ServerLevel level = server.getLevel(job.dimension);
				if (level == null) return;
				for (Op op : batch) level.setBlock(op.pos(), op.state(), SKIP_UPDATES);
			});
			job.next = to;
		} else {
			ClientPacketListener connection = mc.getConnection();
			if (!job.feedbackDisabled) {
				// Sinon chaque /setblock écrit une ligne dans le chat.
				connection.sendCommand("gamerule send_command_feedback false");
				job.feedbackDisabled = true;
			}
			for (int sent = 0; sent < COMMANDS_PER_TICK && job.next < job.ops.size(); sent++) {
				connection.sendCommand(nextCommand(job));
			}
		}

		if (job.next >= job.ops.size()) {
			current = null;
			restoreFeedback(job);
			SchemaCraftClient.message(Component.literal(job.undo
				? "Collage annulé (" + job.ops.size() + " blocs restaurés)"
				: "Collé : " + job.ops.size() + " blocs  (Ctrl+Z pour annuler)").withStyle(ChatFormatting.GREEN));
		}
	}

	private static void restoreFeedback(Job job) {
		if (!job.feedbackDisabled) return;
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection != null) connection.sendCommand("gamerule send_command_feedback true");
		job.feedbackDisabled = false;
	}

	/** Regroupe les blocs identiques qui se suivent sur l'axe X en un seul /fill. */
	private static String nextCommand(Job job) {
		Op first = job.ops.get(job.next);
		int end = job.next + 1;
		BlockPos last = first.pos();
		while (end < job.ops.size()) {
			Op op = job.ops.get(end);
			BlockPos p = op.pos();
			if (op.state() != first.state() || p.getY() != last.getY() || p.getZ() != last.getZ() || p.getX() != last.getX() + 1) break;
			last = p;
			end++;
		}
		job.next = end;
		String state = BlockStateParser.serialize(first.state());
		BlockPos a = first.pos();
		if (last.equals(a)) return "setblock " + a.getX() + " " + a.getY() + " " + a.getZ() + " " + state + " strict";
		return "fill " + a.getX() + " " + a.getY() + " " + a.getZ() + " "
			+ last.getX() + " " + last.getY() + " " + last.getZ() + " " + state + " strict";
	}
}
