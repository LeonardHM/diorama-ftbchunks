package dev.diorama.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.DioramaClient;
import dev.diorama.Settings;
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

/**
 * The key that shows or hides waypoints in the world ({@code waypoints-key}), bound by the player
 * since it starts unbound: one press takes the pins, their labels and their beams off the screen and
 * leaves the minimap's alone, the next brings them back.
 */
final class WaypointsKeyTest {
	/** Colours no block or other pin uses, to find each pin's head by. */
	private static final int NEAR = 0xFF00FF;
	private static final int FAR = 0x00FFFF;

	private WaypointsKeyTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("weather clear");
		context.setScreen(() -> null);
		context.waitFor(mc -> mc.player.onGround(), 600);
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		world.getServer().runCommand("tp @a " + (at.getX() + 0.5) + " " + at.getY() + " " + (at.getZ() + 0.5) + " 180 0");
		Settings.Beams beams = context.computeOnClient(mc -> Settings.get().waypointBeams);
		context.runOnClient(mc -> {
			Settings.get().waypointBeams = Settings.Beams.ALL;
			WaypointStore store = WaypointStore.get();
			store.all().stream().toList().forEach(store::remove);
			Waypoint near = new Waypoint("Base", at.getX() - 6, at.getY(), at.getZ() - 14, "minecraft:overworld", NEAR);
			near.initials = "B";
			store.add(near);
			Waypoint far = new Waypoint("Mine", at.getX() + 30, at.getY(), at.getZ() - 60, "minecraft:overworld", FAR);
			far.initials = "M";
			store.add(far);
			DioramaClient.TOGGLE_WAYPOINTS_WORLD.setKey(InputConstants.getKey("key.keyboard.z"));
			net.minecraft.client.KeyMapping.resetMapping();
		});
		// Long enough for the commands' chat lines to fade.
		context.waitTicks(220);
		try {
			BufferedImage shown = read(context.takeScreenshot("waypoints-key-01-shown"));
			if (!has(shown, NEAR) || !has(shown, FAR)) {
				throw new AssertionError("The two waypoints aren't on the screen before the key is pressed");
			}
			context.getInput().pressKey(DioramaClient.TOGGLE_WAYPOINTS_WORLD);
			context.waitTicks(5);
			if (context.computeOnClient(mc -> Settings.get().waypointsInWorld)) {
				throw new AssertionError("Pressing the key didn't turn In the world off");
			}
			// The minimap is in the top right corner with both pins on it: only the world's go.
			BufferedImage hidden = read(context.takeScreenshot("waypoints-key-02-hidden"));
			int minimapLeft = hidden.getWidth() * 3 / 4;
			if (has(hidden.getSubimage(0, 0, minimapLeft, hidden.getHeight()), NEAR) || has(hidden.getSubimage(0, 0, minimapLeft, hidden.getHeight()), FAR)) {
				throw new AssertionError("A waypoint is still drawn in the world after the key hid them");
			}
			context.getInput().pressKey(DioramaClient.TOGGLE_WAYPOINTS_WORLD);
			context.waitTicks(5);
			BufferedImage back = read(context.takeScreenshot("waypoints-key-03-back"));
			if (!context.computeOnClient(mc -> Settings.get().waypointsInWorld) || !has(back, NEAR) || !has(back, FAR)) {
				throw new AssertionError("A second press didn't bring the waypoints in the world back");
			}
		} finally {
			context.runOnClient(mc -> {
				Settings.get().waypointsInWorld = true;
				Settings.get().waypointBeams = beams;
				WaypointStore store = WaypointStore.get();
				store.all().stream().toList().forEach(store::remove);
				DioramaClient.TOGGLE_WAYPOINTS_WORLD.setKey(InputConstants.UNKNOWN);
				net.minecraft.client.KeyMapping.resetMapping();
			});
		}
	}

	/** Whether enough of the picture is this colour to be a pin's head, and not a stray pixel. */
	private static boolean has(BufferedImage shot, int rgb) {
		int count = 0;
		for (int y = 0; y < shot.getHeight(); y++) {
			for (int x = 0; x < shot.getWidth(); x++) {
				int argb = shot.getRGB(x, y);
				if (Math.abs((argb >> 16 & 0xFF) - (rgb >> 16 & 0xFF)) <= 4 && Math.abs((argb >> 8 & 0xFF) - (rgb >> 8 & 0xFF)) <= 4 && Math.abs((argb & 0xFF) - (rgb & 0xFF)) <= 4) {
					count++;
				}
			}
		}
		return count >= 12;
	}

	private static BufferedImage read(Path file) {
		try {
			return ImageIO.read(file.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
