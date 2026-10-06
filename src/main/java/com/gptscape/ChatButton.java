package com.gptscape;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import javax.swing.Icon;
import javax.swing.JButton;

/** Botão compacto e redondo com ícone, no estilo dos chats modernos. */
final class ChatButton extends JButton
{
	enum Style
	{
		/** Só o ícone; círculo discreto ao passar o mouse. */
		GHOST,
		/** Círculo preenchido (botão de enviar/parar). */
		FILLED
	}

	private static final Color FILLED_ENABLED = new Color(226, 226, 226);
	private static final Color FILLED_DISABLED = new Color(62, 62, 62);

	private Style style;

	ChatButton(Icon icon, Icon disabledIcon, String tooltip, Style style, int size)
	{
		super(icon);
		this.style = style;
		setDisabledIcon(disabledIcon);
		setToolTipText(tooltip);
		setPreferredSize(new Dimension(size, size));
		setMinimumSize(new Dimension(size, size));
		setContentAreaFilled(false);
		setBorderPainted(false);
		setFocusPainted(false);
		setOpaque(false);
		setRolloverEnabled(true);
	}

	void setAppearance(Icon icon, Icon disabledIcon, String tooltip)
	{
		setIcon(icon);
		setDisabledIcon(disabledIcon);
		setToolTipText(tooltip);
		repaint();
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int d = Math.min(getWidth(), getHeight()) - 2;
			float x = (getWidth() - d) / 2f;
			float y = (getHeight() - d) / 2f;

			if (style == Style.FILLED)
			{
				g2.setColor(isEnabled() ? FILLED_ENABLED : FILLED_DISABLED);
				g2.fill(new Ellipse2D.Float(x, y, d, d));
			}
			else if (isEnabled() && (getModel().isRollover() || getModel().isPressed()))
			{
				g2.setColor(ChatTheme.HOVER);
				g2.fill(new Ellipse2D.Float(x, y, d, d));
			}

			Icon icon = isEnabled() || getDisabledIcon() == null ? getIcon() : getDisabledIcon();
			if (icon != null)
			{
				icon.paintIcon(this, g2, (getWidth() - icon.getIconWidth()) / 2, (getHeight() - icon.getIconHeight()) / 2);
			}
		}
		finally
		{
			g2.dispose();
		}
	}
}
