package dev.diorama.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.diorama.ChunkyBorders;
import dev.diorama.Diorama;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.WorldBorderOverlay;
import dev.diorama.bake.TileKey;
import dev.diorama.compat.ChunkyBorder;
import dev.diorama.drawing.Drawing;
import dev.diorama.web.ClientWebMap;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Chunky Border's border on the map (opt-in: {@code chunky-border}), set with the add-on's own
 * commands, a square and then a curve around the player, on the fullscreen map, the minimap and the
 * web page. Two ways, as the player learns the border two ways:
 * <ul>
 *   <li>Singleplayer, the add-on in the game ({@code --mods chunky-border}): Diorama reads the border
 *       its client keeps. Also turned to face east, and on the player's own web map.</li>
 *   <li>A real server with the add-on, the player without it ({@code --server-mods chunky-border --env
 *       DIORAMA_REAL_SERVER=1}): Diorama reads the add-on's message itself, which only a real server
 *       sends over the network; and the server's web map shows the border.</li>
 * </ul>
 * Pictures in {@code screenshots/chunky-border/}.
 */
final class ChunkyBorderTest {
	/** The colour of the wall unless the player set another: the game's blue. */
	private static final int BLUE = 0x20A0FF;
	private static final int RADIUS = 32;
	private static final String OVERWORLD = "minecraft:overworld";

	private ChunkyBorderTest() {
	}

	static void singleplayer(ClientGameTestContext context, TestSingleplayerContext world) {
		if (!ChunkyBorders.addOnInstalled()) {
			throw new AssertionError("chunky-border in singleplayer needs Chunky Border in the game: run it with --mods chunky-border");
		}
		Path out = folder();
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("difficulty peaceful");
		int ground = world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, -2, -2));
		// Looking straight down, so the add-on's own blue wall is in none of the pictures that count blue.
		world.getServer().runCommand("tp @a -1.5 " + ground + " -1.5 180 90");
		context.waitFor(mc -> mc.player.onGround() && mc.player.blockPosition().getX() == -2 && mc.player.blockPosition().getZ() == -2, 600);
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		int cx = at.getX();
		int cz = at.getZ();

		// As players set one: Chunky's selection, then the border.
		for (String command : new String[] {"chunky center " + cx + " " + cz, "chunky radius " + RADIUS, "chunky shape square", "chunky border add"}) {
			world.getServer().runCommand(command);
		}
		ChunkyBorder.Shape square = waitForShape(context, shape -> shape.corners() == 4, "a square");
		checkSquare(square, cx, cz);
		DioramaClientTest.settle(context, 40);
		List<String> wrong = new ArrayList<>();
		checkLines(context, at, square, "facing north", wrong);
		pictures(context, at, "square", wrong);

		// Turned to face east: the line is on the same ground, which is elsewhere on the picture.
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).turn(1));
		context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 100, 2400);
		DioramaClientTest.settle(context, 40);
		checkLines(context, at, square, "facing east", wrong);
		WorldBorderTest.map(context, at, 4, TileKey.SURFACE, "chunky-map-square-east");

		// The player's own web map, facing east: the page puts the line where the map does.
		HttpClient http = HttpClient.newHttpClient();
		int port = WebMapMarkersTest.freePort();
		context.runOnClient(mc -> {
			Settings.get().webMapPort = port;
			Settings.get().webMap = Settings.WebMap.THIS_COMPUTER;
		});
		context.waitFor(mc -> ClientWebMap.servingPort() != 0, 200);
		int served = ClientWebMap.servingPort();
		checkPage(context, http, served, square, "the player's own web map facing east", wrong);
		ClientWebMapTest.photograph("http://127.0.0.1:" + served + "/", out.resolve("client-square-east.png"));
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).turn(-1));
		context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 100, 2400);
		context.setScreen(() -> null);
		DioramaClientTest.settle(context, 40);
		checkPage(context, http, served, square, "the player's own web map", wrong);
		ClientWebMapTest.photograph("http://127.0.0.1:" + served + "/", out.resolve("client-square.png"));

		// A circle: round on the map and the page.
		world.getServer().runCommand("chunky shape circle");
		world.getServer().runCommand("chunky border add");
		ChunkyBorder.Shape circle = waitForShape(context, shape -> shape.corners() > 16, "a circle");
		checkRound(circle, cx, cz);
		DioramaClientTest.settle(context, 20);
		checkLines(context, at, circle, "a circle", wrong);
		pictures(context, at, "circle", wrong);
		checkPage(context, http, served, circle, "the player's own web map, a circle", wrong);
		ClientWebMapTest.photograph("http://127.0.0.1:" + served + "/", out.resolve("client-circle.png"));

		// Taken away: no line, on the map or the page.
		world.getServer().runCommand("chunky border remove");
		context.waitFor(mc -> ChunkyBorders.shape(mc.level) == null, 400);
		int left = context.computeOnClient(mc -> new WorldBorderOverlay().sides(WorldBorderTest.around(cx, at.getY(), cz, 2), mc.level, mc.player).size());
		if (left != 0) {
			wrong.add("With the border removed, the map still has " + left + " lines");
		}
		context.waitTicks(10);
		String none = WebMapMarkersTest.get(http, served, "/border.json");
		if (!none.equals("{\"lines\":[]}")) {
			wrong.add("With the border removed, the page's border.json is " + none);
		}
		context.runOnClient(mc -> Settings.get().webMap = Settings.WebMap.OFF);
		context.waitTicks(3);
		if (!wrong.isEmpty()) {
			throw new AssertionError("Chunky Border's border on the map is wrong:\n" + String.join("\n", wrong));
		}
	}

	static void server(ClientGameTestContext context) {
		if (System.getenv("DIORAMA_REAL_SERVER") == null) {
			throw new AssertionError("chunky-border on a server needs a real server: run it with --env DIORAMA_REAL_SERVER=1");
		}
		if (ChunkyBorders.addOnInstalled()) {
			throw new AssertionError("chunky-border on a server is for a player without the add-on: run it with --server-mods chunky-border, not --mods");
		}
		Path out = folder();
		Path config = Path.of(System.getProperty("user.home"), "real-servers", System.getenv("DIORAMA_NODE"), "config", "diorama-server.properties");
		int port = WebMapMarkersTest.freePort();
		String page = "http://127.0.0.1:" + port + "/";
		WebMapMarkersTest.write(config, "web-map=true\nweb-map-port=" + port + "\nweb-map-address=127.0.0.1\nshare-land=visited\n");
		HttpClient http = HttpClient.newHttpClient();
		List<String> wrong = new ArrayList<>();
		try (RealServer server = RealServer.start(true)) {
			server.command("time set noon");
			server.command("difficulty peaceful");
			server.join(context);
			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 50, 2400);
			BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
			int cx = at.getX();
			int cz = at.getZ();
			// Looking straight down, as in singleplayer.
			server.command("tp @a " + (cx + 0.5) + " " + at.getY() + " " + (cz + 0.5) + " 180 90");
			for (String command : new String[] {"chunky center " + cx + " " + cz, "chunky radius " + RADIUS, "chunky shape square", "chunky border add"}) {
				server.command(command);
			}
			// The add-on's message, read off the network by Diorama itself.
			ChunkyBorder.Shape square = fromServer(context, server, shape -> shape.corners() == 4, "a square from the server");
			ChunkyBorder.Shape heard = context.computeOnClient(mc -> ChunkyBorders.heardForTests().get(OVERWORLD));
			if (heard == null || !heard.same(square)) {
				wrong.add("The square isn't the one heard on the channel: " + heard);
			}
			checkSquare(square, cx, cz);
			DioramaClientTest.settle(context, 40);
			checkLines(context, at, square, "from the server", wrong);
			pictures(context, at, "server-square", wrong);

			// The server's web map: the land the player stood on is drawn once it's saved.
			context.waitTicks(200);
			server.commandAndWait("save-all flush", "Saved the game", 300);
			server.command("diorama-web redraw");
			WebMapMarkersTest.waitForPass(context, http, port, server);
			checkPage(context, http, port, square, "the server's web map", wrong);
			String view = "#dim=" + OVERWORLD + "&u=" + cx + "&v=" + Math.round(Projection.v(cx, at.getY(), cz)) + "&s=4";
			ClientWebMapTest.photograph(page + view, out.resolve("server-square.png"));

			// A star, as the add-on makes it: ten corners.
			server.command("chunky shape star");
			server.command("chunky border add");
			ChunkyBorder.Shape star = fromServer(context, server, shape -> shape.corners() == 10, "a star from the server");
			DioramaClientTest.settle(context, 20);
			checkLines(context, at, star, "a star from the server", wrong);
			pictures(context, at, "server-star", wrong);
			checkPage(context, http, port, star, "the server's web map, a star", wrong);
			ClientWebMapTest.photograph(page + view, out.resolve("server-star.png"));

			server.command("chunky border remove");
			try {
				context.waitFor(mc -> ChunkyBorders.shape(mc.level) == null, 300);
			} catch (AssertionError e) {
				// As a new border, on the add-on's older NeoForge builds: gone once the player joins again.
				RealServer.leave(context);
				server.join(context);
				context.waitFor(mc -> MapSession.get() != null, 2400);
				context.waitTicks(100);
				if (context.computeOnClient(mc -> ChunkyBorders.shape(mc.level)) != null) {
					wrong.add("The removed border is still on the map after joining again");
				}
			}
			String[] text = {""};
			int[] asked = {0};
			try {
				context.waitFor(mc -> asked[0]++ % 20 == 0 && (text[0] = WebMapMarkersTest.get(http, port, "/border.json")).equals("{\"lines\":[]}"), 1200);
			} catch (AssertionError e) {
				wrong.add("With the border removed, the server's border.json is " + text[0]);
			}
			RealServer.leave(context);
		}
		try {
			Files.deleteIfExists(config);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		if (context.computeOnClient(mc -> !ChunkyBorders.heardForTests().isEmpty())) {
			wrong.add("The server's borders are still known after leaving it");
		}
		if (!wrong.isEmpty()) {
			throw new AssertionError("Chunky Border's border on the map is wrong:\n" + String.join("\n", wrong));
		}
	}

	private static Path folder() {
		Path out = Path.of("screenshots", "chunky-border");
		try {
			Files.createDirectories(out);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return out;
	}

	/**
	 * The border the server sends. The add-on's NeoForge builds up to 1.2.23 send it only as a player
	 * enters a dimension, not when it changes: there the player meets a new border by joining again.
	 */
	private static ChunkyBorder.Shape fromServer(ClientGameTestContext context, RealServer server, Predicate<ChunkyBorder.Shape> wanted, String what) {
		try {
			return waitForShape(context, wanted, what, 300);
		} catch (AssertionError e) {
			Diorama.LOGGER.info("chunky-border: {} didn't come while the player was there: joining again", what);
			BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
			RealServer.leave(context);
			server.join(context);
			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 50, 2400);
			server.command("tp @a " + (at.getX() + 0.5) + " " + at.getY() + " " + (at.getZ() + 0.5) + " 180 90");
			return waitForShape(context, wanted, what);
		}
	}

	private static ChunkyBorder.Shape waitForShape(ClientGameTestContext context, Predicate<ChunkyBorder.Shape> wanted, String what) {
		return waitForShape(context, wanted, what, 1200);
	}

	private static ChunkyBorder.Shape waitForShape(ClientGameTestContext context, Predicate<ChunkyBorder.Shape> wanted, String what, int ticks) {
		try {
			context.waitFor(mc -> {
				ChunkyBorder.Shape shape = ChunkyBorders.shape(mc.level);
				return shape != null && wanted.test(shape);
			}, ticks);
		} catch (AssertionError e) {
			throw new AssertionError("The map never had " + what + " from Chunky Border; it has " + context.computeOnClient(mc -> describe(ChunkyBorders.shape(mc.level)))
				+ "; heard on the channel for " + context.computeOnClient(mc -> ChunkyBorders.heardForTests().keySet()) + " in " + context.computeOnClient(mc -> mc.level.dimension().identifier())
				+ listening(context), e);
		}
		return context.computeOnClient(mc -> ChunkyBorders.shape(mc.level));
	}

	/** On NeoForge, whether the server agreed it sends on the channel, which its add-on asks before it sends. */
	private static String listening(ClientGameTestContext context) {
		//? if neoforge {
		/*return "; the server " + (context.computeOnClient(mc -> mc.getConnection() != null && mc.getConnection().hasChannel(ChunkyBorders.CHANNEL)) ? "has" : "hasn't") + " the channel";
		*///?} else {
		return "";
		//?}
	}

	private static String describe(ChunkyBorder.Shape shape) {
		return shape == null ? "none" : shape.corners() + " corners from " + shape.xs()[0] + ", " + shape.zs()[0];
	}

	/** Chunky's square of radius r around a block: its corners are r blocks each way from the block's corner. */
	private static void checkSquare(ChunkyBorder.Shape square, int cx, int cz) {
		for (int i = 0; i < 4; i++) {
			if (Math.abs(Math.abs(square.xs()[i] - cx) - RADIUS) > 1e-6 || Math.abs(Math.abs(square.zs()[i] - cz) - RADIUS) > 1e-6) {
				throw new AssertionError("The square's corner " + square.xs()[i] + ", " + square.zs()[i] + " isn't " + RADIUS + " blocks each way from " + cx + ", " + cz);
			}
		}
	}

	private static void checkRound(ChunkyBorder.Shape circle, int cx, int cz) {
		for (int i = 0; i < circle.corners(); i++) {
			double r = Math.hypot(circle.xs()[i] - cx, circle.zs()[i] - cz);
			if (Math.abs(r - RADIUS) > 1e-6) {
				throw new AssertionError("The circle's corner " + circle.xs()[i] + ", " + circle.zs()[i] + " is " + r + " from " + cx + ", " + cz);
			}
		}
	}

	/**
	 * The lines the map draws around the player, at the map's facing: every corner of the border is
	 * a point of them, at its column, and on ground between the world's floor and its top.
	 */
	private static void checkLines(ClientGameTestContext context, BlockPos at, ChunkyBorder.Shape shape, String when, List<String> wrong) {
		String found = context.computeOnClient(mc -> {
			List<Drawing> lines = new WorldBorderOverlay().sides(WorldBorderTest.around(at.getX(), at.getY(), at.getZ(), 1), mc.level, mc.player);
			if (lines.isEmpty()) {
				return "no lines";
			}
			int color = lines.get(0).color & 0xFFFFFF;
			if (color != BLUE) {
				return String.format("lines in %06x, not the wall's %06x", color, BLUE);
			}
			for (int i = 0; i < shape.corners(); i++) {
				double u = Projection.u(shape.xs()[i], shape.zs()[i]);
				double high = Projection.v(shape.xs()[i], mc.level.getMaxY() + 1, shape.zs()[i]);
				double low = Projection.v(shape.xs()[i], mc.level.getMinY(), shape.zs()[i]);
				boolean there = false;
				for (Drawing line : lines) {
					for (int p = 0; p + 1 < line.points.length && !there; p += 2) {
						there = Math.abs(line.points[p] - u) < 1e-6 && line.points[p + 1] >= high && line.points[p + 1] <= low;
					}
				}
				if (!there) {
					return "no point at the corner " + shape.xs()[i] + ", " + shape.zs()[i] + " (map column " + u + ") in " + lines.size() + " lines";
				}
			}
			return null;
		});
		if (found != null) {
			wrong.add(when + ": " + found);
		}
	}

	/** The minimap and the fullscreen map with the border on them, in its blue. */
	private static void pictures(ClientGameTestContext context, BlockPos at, String name, List<String> wrong) {
		context.setScreen(() -> null);
		context.runOnClient(mc -> Settings.get().minimapShape = Settings.Shape.SQUARE);
		context.waitTicks(4);
		int minimap = WorldBorderTest.count(WorldBorderTest.read(context.takeScreenshot("chunky-minimap-" + name)), BLUE);
		int map = WorldBorderTest.count(WorldBorderTest.map(context, at, 4, TileKey.SURFACE, "chunky-map-" + name), BLUE);
		WorldBorderTest.map(context, at, 1.5, TileKey.SURFACE, "chunky-map-" + name + "-whole");
		context.setScreen(() -> null);
		Diorama.LOGGER.info("chunky-border {}: {} blue pixels on the minimap, {} on the map", name, minimap, map);
		if (minimap < 40 || map < 400) {
			wrong.add(name + ": " + minimap + " blue pixels on the minimap and " + map + " on the map");
		}
	}

	/**
	 * The page's border.json: one line, in the overworld, in the wall's blue, closed, through every
	 * corner of the border at the map's facing, as the map in the game has it.
	 */
	private static void checkPage(ClientGameTestContext context, HttpClient http, int port, ChunkyBorder.Shape shape, String where, List<String> wrong) {
		double[] corners = context.computeOnClient(mc -> {
			double[] us = new double[shape.corners()];
			for (int i = 0; i < us.length; i++) {
				us[i] = Projection.u(shape.xs()[i], shape.zs()[i]);
			}
			return us;
		});
		String[] text = {""};
		String[] why = {"not asked yet"};
		int[] asked = {0};
		try {
			// The server's web map asks the add-on every few seconds; the player's own makes the line again every few.
			context.waitFor(mc -> {
				if (asked[0]++ % 20 != 0) {
					return false;
				}
				text[0] = WebMapMarkersTest.status(http, port, "/border.json") == 200 ? WebMapMarkersTest.get(http, port, "/border.json") : "";
				why[0] = wrongLine(text[0], corners);
				return why[0] == null;
			}, 1200);
		} catch (AssertionError e) {
			wrong.add(where + ": " + why[0] + ": " + text[0].substring(0, Math.min(300, text[0].length())));
		}
	}

	private static String wrongLine(String text, double[] corners) {
		if (text.isEmpty()) {
			return "no border.json";
		}
		JsonArray lines = JsonParser.parseString(text).getAsJsonObject().getAsJsonArray("lines");
		if (lines.size() != 1) {
			return lines.size() + " lines";
		}
		JsonObject line = lines.get(0).getAsJsonObject();
		if (!line.get("dimension").getAsString().equals(OVERWORLD) || !line.get("color").getAsString().equalsIgnoreCase(String.format("#%06x", BLUE))) {
			return "not a blue line in the overworld";
		}
		JsonArray points = line.getAsJsonArray("points");
		int n = points.size();
		if (n < 8 || points.get(0).getAsDouble() != points.get(n - 2).getAsDouble() || points.get(1).getAsDouble() != points.get(n - 1).getAsDouble()) {
			return "not a closed line";
		}
		for (JsonElement point : points) {
			if (!Double.isFinite(point.getAsDouble())) {
				return "a point isn't a number";
			}
		}
		// Across, the line spans the border's corners exactly, to the tenth of a block the page gets.
		double minU = Double.MAX_VALUE;
		double maxU = -Double.MAX_VALUE;
		for (double u : corners) {
			minU = Math.min(minU, u);
			maxU = Math.max(maxU, u);
		}
		double lineMin = Double.MAX_VALUE;
		double lineMax = -Double.MAX_VALUE;
		for (int i = 0; i < n; i += 2) {
			lineMin = Math.min(lineMin, points.get(i).getAsDouble());
			lineMax = Math.max(lineMax, points.get(i).getAsDouble());
		}
		if (Math.abs(lineMin - minU) > 0.051 || Math.abs(lineMax - maxU) > 0.051) {
			return "the line spans map columns " + lineMin + " to " + lineMax + ", not " + minU + " to " + maxU;
		}
		// A polygon's corners are all there; a curve's many corners may be left out where it hardly bends.
		if (corners.length <= 10) {
			for (double u : corners) {
				boolean there = false;
				for (int i = 0; i < n && !there; i += 2) {
					there = Math.abs(points.get(i).getAsDouble() - u) <= 0.051;
				}
				if (!there) {
					return "no point at map column " + u;
				}
			}
		}
		return null;
	}
}
