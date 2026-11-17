package dev.diorama.test;

import dev.diorama.Settings;
import dev.diorama.SettingsScreen;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointStore;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * How solid waypoints are in the world (opt-in: {@code waypoint-opacity}, #412). The slider on the
 * Waypoints tab is dragged to its low end, and the screenshot is compared with the same view with
 * the waypoints solid and with none: at 40% each pixel of a pin's head is four parts pin to six
 * parts of what's behind it. The minimap's pins stay solid.
 */
final class WaypointOpacityTest {
	/** Colours no block or other pin uses, to find each pin's head by. */
	private static final int NEAR = 0xFF00FF;
	private static final int FAR = 0x00FFFF;

	private WaypointOpacityTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("weather clear");
		world.getServer().runCommand("difficulty peaceful");
		int ground = world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, -2, -2));
		// A lit stone floor and wall: something to see through a pin, and nothing that moves.
		world.getServer().runCommand("fill -20 " + ground + " -40 26 " + (ground + 12) + " 2 minecraft:air");
		world.getServer().runCommand("fill -20 " + (ground - 1) + " -40 26 " + (ground - 1) + " 2 minecraft:smooth_stone");
		world.getServer().runCommand("fill -20 " + ground + " -40 26 " + (ground + 12) + " -40 minecraft:bricks");
		world.getServer().runCommand("tp @a -1.5 " + ground + " -1.5 180 10");
		context.waitFor(mc -> mc.player.onGround() && mc.player.blockPosition().getX() == -2 && mc.player.blockPosition().getZ() == -2, 600);
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		Settings.WaypointLabels labels = context.computeOnClient(mc -> Settings.get().waypointLabels);
		Settings.Beams beams = context.computeOnClient(mc -> Settings.get().waypointBeams);
		int minimapOpacity = context.computeOnClient(mc -> Settings.get().minimapOpacity);
		context.runOnClient(mc -> {
			Settings settings = Settings.get();
			if (settings.waypointWorldOpacity != 100) {
				throw new AssertionError("Waypoints in the world should start solid, not at " + settings.waypointWorldOpacity + "%");
			}
			settings.waypointLabels = Settings.WaypointLabels.ALWAYS;
			// No beams while the pins are measured: a beam stands behind its pin and tints what shows through.
			settings.waypointBeams = Settings.Beams.OFF;
			WaypointStore store = WaypointStore.get();
			store.all().stream().toList().forEach(store::remove);
			Waypoint near = new Waypoint("Base", at.getX() - 4, at.getY(), at.getZ() - 6, "minecraft:overworld", NEAR);
			near.initials = "B";
			store.add(near);
			Waypoint far = new Waypoint("Mine", at.getX() + 12, at.getY(), at.getZ() - 30, "minecraft:overworld", FAR);
			far.initials = "M";
			store.add(far);
		});
		// Long enough for the commands' chat lines to fade.
		context.waitTicks(220);
		try {
			BufferedImage solid = read(context.takeScreenshot("waypoint-opacity-01-solid"));
			context.runOnClient(mc -> Settings.get().waypointsInWorld = false);
			context.waitTicks(4);
			BufferedImage behind = read(context.takeScreenshot("waypoint-opacity-02-none"));
			context.runOnClient(mc -> Settings.get().waypointsInWorld = true);

			// The slider, as a player finds it: the Waypoints tab, beside the teleport command.
			context.setScreen(() -> new SettingsScreen(null));
			context.waitTicks(5);
			DioramaClientTest.click(context, "Waypoints");
			context.getInput().setCursorPos(0, 0);
			context.waitTicks(3);
			context.takeScreenshot("waypoint-opacity-03-settings");
			DioramaClientTest.clickAlong(context, "Opacity: ", 0.0);
			int low = context.computeOnClient(mc -> Settings.get().waypointWorldOpacity);
			if (low != Settings.MIN_OPACITY || context.computeOnClient(mc -> Settings.get().minimapOpacity) != minimapOpacity) {
				throw new AssertionError("The low end of the Waypoints tab's Opacity set waypoints to " + low + "% and the minimap to "
					+ context.computeOnClient(mc -> Settings.get().minimapOpacity) + "%");
			}
			context.getInput().setCursorPos(0, 0);
			context.waitTicks(3);
			context.takeScreenshot("waypoint-opacity-04-settings-low");
			context.setScreen(() -> null);
			context.waitTicks(10);
			context.takeScreenshot("waypoint-opacity-05-lowest");

			for (int percent : new int[] {40, 70}) {
				context.runOnClient(mc -> Settings.get().waypointWorldOpacity = percent);
				context.waitTicks(4);
				BufferedImage shot = read(context.takeScreenshot("waypoint-opacity-06-at-" + percent));
				blended(solid, behind, shot, NEAR, percent, "the near pin");
				blended(solid, behind, shot, FAR, percent, "the far pin");
				// The minimap, in the top right corner, has both pins as solid as before.
				BufferedImage minimap = shot.getSubimage(shot.getWidth() * 3 / 4, 0, shot.getWidth() / 4, shot.getHeight() / 2);
				if (count(minimap, NEAR) < 12 || count(minimap, FAR) < 12) {
					throw new AssertionError("At " + percent + "% in the world the minimap's pins aren't solid any more");
				}
			}
			// With their beams, which fade with the pins.
			context.runOnClient(mc -> Settings.get().waypointBeams = Settings.Beams.ALL);
			for (int percent : new int[] {100, 40}) {
				context.runOnClient(mc -> Settings.get().waypointWorldOpacity = percent);
				context.waitTicks(10);
				context.takeScreenshot("waypoint-opacity-07-beams-at-" + percent);
			}
		} finally {
			context.setScreen(() -> null);
			context.runOnClient(mc -> {
				Settings settings = Settings.get();
				settings.waypointsInWorld = true;
				settings.waypointWorldOpacity = 100;
				settings.minimapOpacity = minimapOpacity;
				settings.waypointLabels = labels;
				settings.waypointBeams = beams;
				settings.save();
				WaypointStore store = WaypointStore.get();
				store.all().stream().toList().forEach(store::remove);
			});
		}
	}

	/**
	 * Where the solid picture has a pin's own colour, away from the minimap, this one has that colour
	 * mixed {@code percent} to the rest with what's behind it.
	 */
	private static void blended(BufferedImage solid, BufferedImage behind, BufferedImage shot, int rgb, int percent, String what) {
		int checked = 0;
		for (int y = 0; y < solid.getHeight(); y++) {
			for (int x = 0; x < solid.getWidth() * 3 / 4; x++) {
				if (!close(solid.getRGB(x, y), rgb, 4)) {
					continue;
				}
				checked++;
				int back = behind.getRGB(x, y);
				int mixed = 0;
				for (int shift = 16; shift >= 0; shift -= 8) {
					mixed |= Math.round(((rgb >> shift & 0xFF) * percent + (back >> shift & 0xFF) * (100 - percent)) / 100.0F) << shift;
				}
				if (!close(shot.getRGB(x, y), mixed, 6)) {
					throw new AssertionError("At " + percent + "% " + what + " at " + x + ", " + y + " is " + Integer.toHexString(shot.getRGB(x, y) & 0xFFFFFF)
						+ ", not " + Integer.toHexString(mixed) + ": " + Integer.toHexString(rgb) + " over " + Integer.toHexString(back & 0xFFFFFF));
				}
			}
		}
		if (checked < 12) {
			throw new AssertionError("The solid picture doesn't show " + what);
		}
	}

	private static int count(BufferedImage shot, int rgb) {
		int count = 0;
		for (int y = 0; y < shot.getHeight(); y++) {
			for (int x = 0; x < shot.getWidth(); x++) {
				if (close(shot.getRGB(x, y), rgb, 4)) {
					count++;
				}
			}
		}
		return count;
	}

	private static boolean close(int argb, int rgb, int within) {
		return Math.abs((argb >> 16 & 0xFF) - (rgb >> 16 & 0xFF)) <= within && Math.abs((argb >> 8 & 0xFF) - (rgb >> 8 & 0xFF)) <= within
			&& Math.abs((argb & 0xFF) - (rgb & 0xFF)) <= within;
	}

	private static BufferedImage read(Path file) {
		try {
			return ImageIO.read(file.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
