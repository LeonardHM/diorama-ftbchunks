package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.bake.TileKey;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * The Fancy fog in a big NeoForge pack. NeoForge 1.21.1 to 1.21.4 moves the whole GUI 200 further
 * from the camera for every HUD layer a mod registers, once there are more than 50, and before
 * 1.21.5 the fog's half-resolution layer was drawn with whatever depth the GUI had: past 145 layers
 * it fell outside the layer's depth range, nothing was drawn, and the map showed its flat backdrop
 * where the fog should be (#383). Here the game gets that many empty HUD layers, and the map's
 * unexplored edge has to show fog. Where the game has no such layers there is nothing to add, and
 * the same picture is checked as it is.
 */
final class FogHudLayersTest {
	/** With the game's own 25 that's 175, well past the 145 where the fog went missing. */
	private static final int EXTRA_LAYERS = 150;

	private FogHudLayersTest() {
	}

	static void run(ClientGameTestContext context) {
		Settings.Quality quality = context.computeOnClient(mc -> Settings.get().quality);
		List<Object> layers = context.computeOnClient(mc -> hudLayers(mc.gui));
		int before = layers == null ? 0 : layers.size();
		//? if neoforge && >=1.21.1 && <1.21.5 {
		/*// Where the bug lived the list has to be found, or this would pass without testing it.
		if (layers == null) {
			throw new AssertionError("NeoForge's HUD layer list wasn't found on the GUI");
		}
		*///?}
		try {
			if (layers != null) {
				context.runOnClient(mc -> addEmptyLayers(layers));
			}
			context.runOnClient(mc -> {
				Settings.get().quality = Settings.Quality.FANCY;
				Settings.get().apply();
			});
			context.setScreen(MapScreen::new);
			// Zoomed out, so land nobody has been to reaches the screen's left and right edges.
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(mc.player.getX(), Projection.v(mc.player.getY(), mc.player.getZ()), 0.75, TileKey.SURFACE));
			DioramaClientTest.settle(context, 80);
			BufferedImage shot = read(context.takeScreenshot("fog-hud-layers-01-fancy"));
			// The fog's banks and their shading differ from pixel to pixel; the map's backdrop without
			// the fog is one colour. A column near the left edge, clear of the bars at top and bottom.
			Set<Integer> colours = new HashSet<>();
			int x = shot.getWidth() / 40;
			for (int y = shot.getHeight() / 5; y < shot.getHeight() * 4 / 5; y++) {
				colours.add(shot.getRGB(x, y) & 0xFFFFFF);
			}
			if (colours.size() < 16) {
				throw new AssertionError("No fog at the Fancy map's unexplored edge with " + (layers == null ? "the game's own" : before + EXTRA_LAYERS)
					+ " HUD layers: " + colours.size() + " colour(s) down the column at x " + x + ", first " + String.format("%06X", colours.iterator().next()));
			}
			System.out.println("[diorama-test] fog with HUD layers: " + colours.size() + " colours at the unexplored edge, "
				+ (layers == null ? "no HUD layer list here" : (before + EXTRA_LAYERS) + " HUD layers"));
		} finally {
			context.setScreen(() -> null);
			if (layers != null) {
				context.runOnClient(mc -> {
					while (layers.size() > before) {
						layers.remove(layers.size() - 1);
					}
				});
			}
			context.runOnClient(mc -> {
				Settings.get().quality = quality;
				Settings.get().apply();
			});
		}
	}

	/** NeoForge's list of HUD layers, the one its GUI depth is counted from; null where there is none. */
	@SuppressWarnings("unchecked")
	private static List<Object> hudLayers(Object gui) {
		try {
			for (Field field : gui.getClass().getDeclaredFields()) {
				if (field.getType().getSimpleName().equals("GuiLayerManager")) {
					field.setAccessible(true);
					Object manager = field.get(gui);
					Field list = manager.getClass().getDeclaredField("layers");
					list.setAccessible(true);
					return (List<Object>) list.get(manager);
				}
			}
			return null;
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	/** Layers that draw nothing, named like the first one: what a mod's hidden HUD part amounts to. */
	private static void addEmptyLayers(List<Object> layers) {
		try {
			Object first = layers.get(0);
			Constructor<?> constructor = first.getClass().getDeclaredConstructors()[0];
			constructor.setAccessible(true);
			Class<?> layerType = constructor.getParameterTypes()[1];
			Object name = first.getClass().getDeclaredMethod("name").invoke(first);
			Object nothing = Proxy.newProxyInstance(layerType.getClassLoader(), new Class<?>[] {layerType}, (proxy, method, args) -> null);
			for (int i = 0; i < EXTRA_LAYERS; i++) {
				layers.add(constructor.newInstance(name, nothing));
			}
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private static BufferedImage read(Path file) {
		try {
			return ImageIO.read(file.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
