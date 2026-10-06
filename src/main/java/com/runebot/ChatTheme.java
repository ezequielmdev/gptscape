package com.runebot;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import net.runelite.client.ui.ColorScheme;

/** Cores e fontes do chat: experiência do Gemini com a paleta escura do RuneLite. */
final class ChatTheme
{
	static final Color BACKGROUND = ColorScheme.DARK_GRAY_COLOR;
	static final Color SURFACE = ColorScheme.DARKER_GRAY_COLOR;
	static final Color USER_BUBBLE = new Color(58, 58, 58);
	static final Color BORDER = ColorScheme.MEDIUM_GRAY_COLOR;
	static final Color TEXT = new Color(226, 226, 226);
	static final Color MUTED = new Color(150, 150, 150);
	static final Color LINK = new Color(122, 176, 255);
	static final Color ERROR = new Color(236, 110, 110);
	static final Color CODE_BACKGROUND = new Color(24, 24, 24);
	static final Color CODE_HEADER = new Color(33, 33, 33);
	static final Color INLINE_CODE = new Color(232, 196, 140);
	static final Color HOVER = new Color(255, 255, 255, 24);
	static final Color ACCENT = ColorScheme.BRAND_ORANGE;

	/** Fonte lógica do Java: tem fallback para acentos, CJK e outros alfabetos (a fonte do RuneScape não tem). */
	static final Font BODY_FONT = new Font(Font.DIALOG, Font.PLAIN, 12);
	static final Font SMALL_FONT = new Font(Font.DIALOG, Font.PLAIN, 11);
	static final Font CODE_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 11);

	private ChatTheme()
	{
	}

	/** Texto com quebra de linha automática, selecionável, sem aparência de campo de edição. */
	static JTextArea wrappingText(String text, Color color, Font font)
	{
		JTextArea area = new JTextArea(text);
		area.setEditable(false);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setOpaque(false);
		area.setBorder(BorderFactory.createEmptyBorder());
		area.setForeground(color);
		area.setFont(font);
		area.setCaretColor(color);
		area.setSelectionColor(new Color(80, 110, 160));
		return area;
	}

	/** Copia texto para a área de transferência do sistema. */
	static boolean copyToClipboard(String text)
	{
		try
		{
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
			return true;
		}
		catch (IllegalStateException e)
		{
			// Área de transferência ocupada por outro programa
			return false;
		}
	}

	/** Ação discreta em forma de link (ex.: "Copy", "Regenerate"). */
	static JLabel actionLink(String text, String tooltip, Runnable action)
	{
		return actionLink(text, tooltip, MUTED, action);
	}

	static JLabel actionLink(String text, String tooltip, Color color, Runnable action)
	{
		JLabel label = new JLabel(text);
		label.setFont(SMALL_FONT);
		label.setForeground(color);
		label.setToolTipText(tooltip);
		label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				action.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				label.setForeground(TEXT);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				label.setForeground(color);
			}
		});
		return label;
	}
}
