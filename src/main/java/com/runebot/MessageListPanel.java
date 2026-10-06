package com.runebot;

import java.awt.Dimension;
import java.awt.Rectangle;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JViewport;
import javax.swing.Scrollable;

/** Lista de mensagens: acompanha a largura do sidebar (line wrap) e rola na vertical. */
final class MessageListPanel extends JPanel implements Scrollable
{
	MessageListPanel()
	{
		super(new StackLayout(18));
		setBackground(ChatTheme.BACKGROUND);
		setBorder(BorderFactory.createEmptyBorder(12, 10, 14, 10));
	}

	@Override
	public Dimension getPreferredScrollableViewportSize()
	{
		return getPreferredSize();
	}

	@Override
	public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
	{
		return 18;
	}

	@Override
	public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
	{
		return Math.max(18, visibleRect.height - 36);
	}

	@Override
	public boolean getScrollableTracksViewportWidth()
	{
		return true;
	}

	@Override
	public boolean getScrollableTracksViewportHeight()
	{
		return getParent() instanceof JViewport && getParent().getHeight() > getPreferredSize().height;
	}
}
