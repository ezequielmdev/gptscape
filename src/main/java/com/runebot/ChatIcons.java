package com.runebot;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import net.runelite.client.util.ImageUtil;

/** Ícones vetoriais desenhados em código (sem imagens externas ou downloads). */
final class ChatIcons
{
	private ChatIcons()
	{
	}

	/** Arquivo do robô (OSRS) incluído no plugin, usado como avatar do assistente. */
	private static final String ASSISTANT_IMAGE = "assistant.png";
	private static final Map<Integer, ImageIcon> ASSISTANT_CACHE = new ConcurrentHashMap<>();

	/** Avatar do assistente (robô do OSRS) no tamanho pedido, reduzido com suavização e guardado em cache. */
	static Icon assistant(int size)
	{
		return ASSISTANT_CACHE.computeIfAbsent(size, s -> new ImageIcon(assistantImage(s)));
	}

	private static BufferedImage assistantImage(int size)
	{
		BufferedImage source = ImageUtil.loadImageResource(ChatIcons.class, ASSISTANT_IMAGE);
		Image scaled = source.getScaledInstance(size, size, Image.SCALE_SMOOTH);
		BufferedImage result = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = result.createGraphics();
		g.drawImage(scaled, 0, 0, null);
		g.dispose();
		return result;
	}

	static Icon arrowUp(int size, Color color)
	{
		return new PaintedIcon(size, (g, s) ->
		{
			g.setColor(color);
			g.setStroke(new BasicStroke(s * 0.14f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			float c = s / 2f;
			g.draw(new Line2D.Float(c, s * 0.82f, c, s * 0.2f));
			GeneralPath head = new GeneralPath();
			head.moveTo(s * 0.24f, s * 0.46f);
			head.lineTo(c, s * 0.2f);
			head.lineTo(s * 0.76f, s * 0.46f);
			g.draw(head);
		});
	}

	static Icon stop(int size, Color color)
	{
		return new PaintedIcon(size, (g, s) ->
		{
			g.setColor(color);
			float inset = s * 0.26f;
			g.fill(new RoundRectangle2D.Float(inset, inset, s - 2 * inset, s - 2 * inset, s * 0.15f, s * 0.15f));
		});
	}

	static Icon plus(int size, Color color)
	{
		return new PaintedIcon(size, (g, s) ->
		{
			g.setColor(color);
			g.setStroke(new BasicStroke(s * 0.12f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			float c = s / 2f;
			g.draw(new Line2D.Float(c, s * 0.18f, c, s * 0.82f));
			g.draw(new Line2D.Float(s * 0.18f, c, s * 0.82f, c));
		});
	}

	/** Quadrado com lápis: "New chat". */
	static Icon newChat(int size, Color color)
	{
		return new PaintedIcon(size, (g, s) ->
		{
			g.setColor(color);
			g.setStroke(new BasicStroke(s * 0.09f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			GeneralPath box = new GeneralPath();
			box.moveTo(s * 0.5f, s * 0.14f);
			box.lineTo(s * 0.2f, s * 0.14f);
			box.quadTo(s * 0.1f, s * 0.14f, s * 0.1f, s * 0.24f);
			box.lineTo(s * 0.1f, s * 0.8f);
			box.quadTo(s * 0.1f, s * 0.9f, s * 0.2f, s * 0.9f);
			box.lineTo(s * 0.76f, s * 0.9f);
			box.quadTo(s * 0.86f, s * 0.9f, s * 0.86f, s * 0.8f);
			box.lineTo(s * 0.86f, s * 0.52f);
			g.draw(box);
			GeneralPath pencil = new GeneralPath();
			pencil.moveTo(s * 0.4f, s * 0.62f);
			pencil.lineTo(s * 0.42f, s * 0.48f);
			pencil.lineTo(s * 0.8f, s * 0.1f);
			pencil.lineTo(s * 0.92f, s * 0.22f);
			pencil.lineTo(s * 0.54f, s * 0.6f);
			pencil.closePath();
			g.draw(pencil);
		});
	}

	/** Três pontos: menu "More". */
	static Icon more(int size, Color color)
	{
		return new PaintedIcon(size, (g, s) ->
		{
			g.setColor(color);
			float d = s * 0.16f;
			float y = s / 2f - d / 2;
			for (float x : new float[]{s * 0.14f, s / 2f - d / 2, s * 0.86f - d})
			{
				g.fill(new Ellipse2D.Float(x, y, d, d));
			}
		});
	}

	/** Imagem do ícone para o botão da barra lateral do RuneLite. */
	static BufferedImage navigationImage()
	{
		return assistantImage(16);
	}

	private static final class PaintedIcon implements Icon
	{
		private final int size;
		private final BiConsumer<Graphics2D, Float> painter;

		PaintedIcon(int size, BiConsumer<Graphics2D, Float> painter)
		{
			this.size = size;
			this.painter = painter;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
				g2.translate(x, y);
				painter.accept(g2, (float) size);
			}
			finally
			{
				g2.dispose();
			}
		}

		@Override
		public int getIconWidth()
		{
			return size;
		}

		@Override
		public int getIconHeight()
		{
			return size;
		}
	}
}
