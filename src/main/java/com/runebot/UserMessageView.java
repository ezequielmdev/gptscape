package com.runebot;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JPanel;
import javax.swing.JTextArea;

/** Mensagem do usuário: balão arredondado e discreto, alinhado à direita. */
final class UserMessageView extends JPanel
{
	private static final int PAD_X = 10;
	private static final int PAD_Y = 7;
	private static final int ARC = 16;
	private static final double MAX_WIDTH_RATIO = 0.85;

	private final JTextArea text;

	UserMessageView(String content)
	{
		super(null);
		setOpaque(false);
		text = ChatTheme.wrappingText(content, ChatTheme.TEXT, ChatTheme.BODY_FONT);
		add(text);
	}

	private Rectangle bubble(int width)
	{
		int maxText = Math.max(40, (int) (width * MAX_WIDTH_RATIO) - PAD_X * 2);
		int textWidth = Math.min(maxText, naturalWidth());
		text.setSize(textWidth, Short.MAX_VALUE);
		int textHeight = text.getPreferredSize().height;
		int bubbleWidth = textWidth + PAD_X * 2;
		return new Rectangle(width - bubbleWidth, 0, bubbleWidth, textHeight + PAD_Y * 2);
	}

	/** Largura da linha mais longa, sem quebra. */
	private int naturalWidth()
	{
		FontMetrics fm = text.getFontMetrics(text.getFont());
		int max = 0;
		for (String line : text.getText().split("\n", -1))
		{
			max = Math.max(max, fm.stringWidth(line));
		}
		return max + 2;
	}

	@Override
	public Dimension getPreferredSize()
	{
		int width = getWidth() > 0 ? getWidth() : 200;
		return new Dimension(width, bubble(width).height);
	}

	@Override
	public void doLayout()
	{
		Rectangle r = bubble(getWidth());
		text.setBounds(r.x + PAD_X, r.y + PAD_Y, r.width - PAD_X * 2, r.height - PAD_Y * 2);
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			Rectangle r = text.getBounds();
			g2.setColor(ChatTheme.USER_BUBBLE);
			g2.fill(new RoundRectangle2D.Float(r.x - PAD_X, r.y - PAD_Y, r.width + PAD_X * 2, r.height + PAD_Y * 2,
				ARC, ARC));
		}
		finally
		{
			g2.dispose();
		}
	}
}
