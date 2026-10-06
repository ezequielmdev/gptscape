package com.runebot;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Empilha os filhos verticalmente com a largura total do container. A altura de cada filho é
 * calculada para essa largura, o que faz textos com quebra de linha se adaptarem à largura do sidebar.
 */
final class StackLayout implements LayoutManager
{
	private final int gap;

	StackLayout(int gap)
	{
		this.gap = gap;
	}

	@Override
	public Dimension preferredLayoutSize(Container parent)
	{
		synchronized (parent.getTreeLock())
		{
			Insets insets = parent.getInsets();
			int width = parent.getWidth() > 0 ? parent.getWidth() : PluginPanel.PANEL_WIDTH;
			int inner = Math.max(1, width - insets.left - insets.right);
			int height = insets.top + insets.bottom;
			int visible = 0;
			for (Component c : parent.getComponents())
			{
				if (c.isVisible())
				{
					height += heightFor(c, inner);
					visible++;
				}
			}
			height += gap * Math.max(0, visible - 1);
			return new Dimension(width, height);
		}
	}

	@Override
	public Dimension minimumLayoutSize(Container parent)
	{
		return new Dimension(0, preferredLayoutSize(parent).height);
	}

	@Override
	public void layoutContainer(Container parent)
	{
		synchronized (parent.getTreeLock())
		{
			Insets insets = parent.getInsets();
			int inner = Math.max(1, parent.getWidth() - insets.left - insets.right);
			int y = insets.top;
			for (Component c : parent.getComponents())
			{
				if (!c.isVisible())
				{
					continue;
				}
				int h = heightFor(c, inner);
				c.setBounds(insets.left, y, inner, h);
				y += h + gap;
			}
		}
	}

	/** Altura preferida do componente quando ele tem a largura indicada. */
	static int heightFor(Component c, int width)
	{
		if (c.getWidth() != width)
		{
			// Componentes de texto só calculam a quebra de linha depois de saberem a largura
			c.setSize(width, c.getHeight() > 0 ? c.getHeight() : Short.MAX_VALUE);
		}
		return c.getPreferredSize().height;
	}

	@Override
	public void addLayoutComponent(String name, Component comp)
	{
		// O layout usa apenas a ordem dos filhos; não há restrições por componente
	}

	@Override
	public void removeLayoutComponent(Component comp)
	{
		// O layout usa apenas a ordem dos filhos; não há restrições por componente
	}
}
