package com.schemacraft.gui;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import com.schemacraft.schematic.BlockStates;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;

/**
 * Liste d'autocomplétion sous un champ texte, comme pour les commandes du chat :
 * ↑/↓ ou la molette pour choisir, Tab / Entrée / clic pour compléter, Échap pour fermer.
 * Le reste de la proposition s'affiche en gris dans le champ.
 */
final class Suggestions {
	/** Une proposition : texte inséré et petite indication à droite (nombre de blocs...). */
	record Item(String text, String hint) {}

	/** Propositions pour le texte et le curseur donnés, et la partie du texte qu'elles remplacent. */
	record Result(int start, int end, List<Item> items) {
		static final Result NONE = new Result(0, 0, List.of());
	}

	interface Provider {
		Result get(String value, int cursor);
	}

	private static final int MAX_ROWS = 8;
	private static final int LINE = 12;
	private static final int SELECTED = 0xFFFFFF00;
	private static final int NORMAL = 0xFFAAAAAA;
	private static final int HINT = 0xFF777777;

	private final EditBox box;
	private final Font font;
	private final Provider provider;
	private Result result = Result.NONE;
	private String lastValue;
	private int lastCursor = -1;
	private int selected;
	private int scroll;
	private boolean dismissed;
	/** Position de la liste à la dernière image (pour les clics). */
	private int px, py, pw, ph;
	private int lastMouseX = -1, lastMouseY = -1;

	Suggestions(EditBox box, Font font, Provider provider) {
		this.box = box;
		this.font = font;
		this.provider = provider;
	}

	/** Recalcule les propositions si le texte ou le curseur a bougé. */
	void update() {
		String v = box.getValue();
		int c = box.getCursorPosition();
		if (v.equals(lastValue) && c == lastCursor) return;
		if (!v.equals(lastValue)) dismissed = false;
		lastValue = v;
		lastCursor = c;
		result = provider.get(v, c);
		if (result.items().size() == 1) {
			String current = v.substring(Math.min(result.start(), v.length()), Math.min(result.end(), v.length()));
			if (result.items().get(0).text().equals(BlockStates.normalize(current))) result = Result.NONE;
		}
		selected = 0;
		scroll = 0;
		updateGhost();
	}

	boolean visible() {
		return box.isFocused() && !dismissed && !result.items().isEmpty();
	}

	/** Tab / Entrée / ↑ / ↓ / Échap quand la liste est ouverte. */
	boolean keyPressed(KeyEvent e) {
		update();
		if (!visible()) return false;
		switch (e.key()) {
			case InputConstants.KEY_UP -> move(-1);
			case InputConstants.KEY_DOWN -> move(1);
			case InputConstants.KEY_TAB, InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> accept();
			case InputConstants.KEY_ESCAPE -> {
				dismissed = true;
				box.setSuggestion(null);
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	boolean mouseClicked(double mx, double my) {
		if (!visible() || !inside(mx, my)) return false;
		selected = scroll + (int) ((my - py - 1) / LINE);
		if (selected >= result.items().size()) selected = result.items().size() - 1;
		accept();
		box.setFocused(true);
		return true;
	}

	boolean mouseScrolled(double mx, double my, double dy) {
		if (!visible() || !inside(mx, my)) return false;
		move(dy > 0 ? -1 : 1);
		return true;
	}

	void render(GuiGraphicsExtractor g, int mouseX, int mouseY, int screenW, int screenH) {
		update();
		if (!visible()) return;
		List<Item> items = result.items();
		int rows = Math.min(MAX_ROWS, items.size());
		int w = 0;
		for (Item it : items) w = Math.max(w, font.width(it.text()) + (it.hint().isEmpty() ? 0 : font.width(it.hint()) + 10));
		pw = w + 6;
		ph = rows * LINE + 2;
		String before = lastValue.substring(0, Math.min(result.start(), lastValue.length()));
		px = Math.min(box.getX() + 4 + font.width(before), screenW - pw - 2);
		px = Math.max(2, px);
		py = box.getY() + box.getHeight() + 1;
		if (py + ph > screenH - 14) py = box.getY() - ph - 1;

		boolean mouseMoved = mouseX != lastMouseX || mouseY != lastMouseY;
		lastMouseX = mouseX;
		lastMouseY = mouseY;
		g.nextStratum();
		g.fill(px, py, px + pw, py + ph, 0xF0000000);
		if (scroll > 0) g.fill(px, py, px + pw, py + 1, 0xFFAAAAAA);
		if (scroll + rows < items.size()) g.fill(px, py + ph - 1, px + pw, py + ph, 0xFFAAAAAA);
		for (int i = 0; i < rows; i++) {
			int index = scroll + i;
			Item it = items.get(index);
			int y = py + 2 + i * LINE;
			boolean hover = mouseMoved && mouseX >= px && mouseX < px + pw && mouseY >= y - 1 && mouseY < y + LINE - 1;
			if (hover && index != selected) {
				selected = index;
				updateGhost();
			}
			g.text(font, it.text(), px + 3, y, index == selected ? SELECTED : NORMAL, true);
			if (!it.hint().isEmpty()) g.text(font, it.hint(), px + pw - 3 - font.width(it.hint()), y, HINT, true);
		}
	}

	private boolean inside(double mx, double my) {
		return mx >= px && mx < px + pw && my >= py && my < py + ph;
	}

	private void move(int delta) {
		int n = result.items().size();
		selected = Math.floorMod(selected + delta, n);
		if (selected < scroll) scroll = selected;
		if (selected >= scroll + MAX_ROWS) scroll = selected - MAX_ROWS + 1;
		updateGhost();
	}

	private void accept() {
		Item it = result.items().get(selected);
		String v = box.getValue();
		int start = Math.min(result.start(), v.length()), end = Math.min(result.end(), v.length());
		String nv = v.substring(0, start) + it.text() + v.substring(end);
		box.setValue(nv);
		box.setCursorPosition(start + it.text().length());
		box.setHighlightPos(box.getCursorPosition());
		lastValue = nv;
		lastCursor = box.getCursorPosition();
		result = Result.NONE;
		box.setSuggestion(null);
	}

	/** Suite grise de la proposition choisie, quand on écrit à la fin du champ. */
	private void updateGhost() {
		String ghost = null;
		if (visible() && lastCursor == lastValue.length()) {
			String typed = lastValue.substring(Math.min(result.start(), lastValue.length()), lastCursor);
			String text = result.items().get(selected).text();
			if (text.startsWith(typed) && text.length() > typed.length()) ghost = text.substring(typed.length());
		}
		box.setSuggestion(ghost);
	}

	// ================================================================ recherche

	/**
	 * Vrai si {@code candidate} commence par {@code typed} ou si un de ses mots (après "_" ou ":")
	 * commence par {@code typed} — comme les suggestions de commandes de Minecraft.
	 */
	static boolean matches(String candidate, String typed) {
		if (typed.isEmpty() || candidate.startsWith(typed)) return true;
		for (int i = 0; i < candidate.length() - 1; i++) {
			char ch = candidate.charAt(i);
			if ((ch == '_' || ch == ':') && candidate.startsWith(typed, i + 1)) return true;
		}
		return false;
	}

	/** Filtre et trie : d'abord ce qui commence par le texte tapé, puis le reste. */
	static List<Item> filter(List<Item> candidates, String typed, int max) {
		String t = BlockStates.normalize(typed);
		List<Item> first = new ArrayList<>(), other = new ArrayList<>();
		for (Item it : candidates) {
			if (it.text().startsWith(t)) first.add(it);
			else if (matches(it.text(), t)) other.add(it);
		}
		first.addAll(other);
		return first.size() > max ? first.subList(0, max) : first;
	}
}
