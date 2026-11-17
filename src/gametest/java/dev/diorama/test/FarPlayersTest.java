package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.TrackedPlayers;
import dev.diorama.bake.TileKey;
import dev.diorama.render.Radar;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Teammates on the map from any distance, on a real server with Diorama (opt-in: {@code
 * far-players}, with {@code --env DIORAMA_REAL_SERVER=1}; with {@code --mods ftb} it has FTB Teams).
 * A second player stands 600 blocks away, far past what the game sends: a vanilla client, or with
 * FTB's mods a second game with them ({@link SecondPlayer}).
 *
 * <ul>
 * <li>The server adds {@code far-players=team} to its settings file. With that, and the two in no
 *     team together, the map shows no one.
 * <li>With FTB Teams: the player makes a party with its own command and the other is added to it.
 *     Now the map has them where they stand, follows them when they move, drops them while they're
 *     in the Nether, and drops them when they leave the party.
 * <li>With Open Parties and Claims ({@code --mods opac}): the same with its party, joined by the
 *     other player's own command, and each player's own setting for sharing and receiving locations.
 * <li>{@code far-players=everyone} shows them with no team at all, and {@code off} no one.
 * </ul>
 */
final class FarPlayersTest {
	private static final String OTHER = "Alice";
	private static final int FAR = 600;

	private FarPlayersTest() {
	}

	static void run(ClientGameTestContext context) {
		if (System.getenv("DIORAMA_REAL_SERVER") == null) {
			throw new AssertionError("far-players needs a real server: run it with --env DIORAMA_REAL_SERVER=1");
		}
		Path home = Path.of(System.getProperty("user.home"), "real-servers", System.getenv("DIORAMA_NODE"));
		Path config = home.resolve("config").resolve("diorama-server.properties");
		boolean ftb = context.computeOnClient(mc -> dev.diorama.compat.FtbChunks.installed());
		boolean opac = context.computeOnClient(mc -> dev.diorama.compat.OpenPartiesAndClaims.installed());
		boolean modded = ftb || opac;
		List<String> wrong = new ArrayList<>();
		Settings.NameTags names = context.computeOnClient(mc -> Settings.get().playerNames);
		context.runOnClient(mc -> Settings.get().playerNames = Settings.NameTags.ALWAYS);

		// As a server owner finds it: nothing set.
		delete(config);
		try (RealServer server = RealServer.start(true)) {
			// Written as the server finishes starting, which is when the test hears it's up.
			for (int i = 0; i < 40 && !(Files.exists(config) && read(config).contains("\nfar-players=team\n")); i++) {
				context.waitTicks(5);
			}
			if (!Files.exists(config) || !read(config).contains("\nfar-players=team\n")) {
				wrong.add("the server didn't add far-players=team to its settings file:\n" + (Files.exists(config) ? read(config) : "(no file)"));
			}
			server.command("time set noon");
			server.join(context);
			String me = context.computeOnClient(mc -> mc.player.getScoreboardName());
			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 50, 2400);
			int[] at = context.computeOnClient(mc -> new int[] {mc.player.blockPosition().getX(), mc.player.blockPosition().getY(), mc.player.blockPosition().getZ()});
			AutoCloseable other = joins(server, modded);
			server.command("gamemode creative " + OTHER);
			int x = at[0] + FAR;
			server.commandAndWait("tp " + OTHER + " " + x + " 200 " + at[2], "Teleported " + OTHER, 120);
			// Long enough for several sends: the two share no team.
			context.waitTicks(100);
			expect(wrong, far(context) == null, "with far-players=team and no team, the map has " + OTHER + " at " + far(context));
			expect(wrong, context.computeOnClient(mc -> TrackedPlayers.fromDiorama().isEmpty()), "with far-players=team and no team, the server says where someone is");
			photograph(context, at, "far-players-01-no-team");

			if (ftb) {
				context.runOnClient(mc -> mc.getConnection().sendCommand("ftbteams party create Crew"));
				// The party's name for commands ends in the start of its ID, which is its file's name.
				Path parties = home.resolve("world").resolve("ftbteams").resolve("party");
				String[] id = {null};
				for (int i = 0; i < 20 && id[0] == null; i++) {
					server.commandAndWait("save-all flush", "Saved the game", 300);
					if (Files.isDirectory(parties)) {
						try (Stream<Path> files = Files.list(parties)) {
							id[0] = files.map(file -> file.getFileName().toString()).filter(name -> name.endsWith(".snbt")).findFirst().orElse(null);
						} catch (IOException e) {
							throw new UncheckedIOException(e);
						}
					}
				}
				if (id[0] == null) {
					throw new AssertionError("FTB Teams saved no party after /ftbteams party create Crew, in " + parties);
				}
				String party = "Crew#" + id[0].substring(0, 8);
				// One of the two is this version's spelling; the other isn't a command.
				server.command("ftbteams force-add " + party + " " + OTHER + "\nftbteams force add " + party + " " + OTHER);
				waitForFar(context, x, at[2], "after joining " + me + "'s FTB party", wrong);
				photograph(context, at, "far-players-02-ftb-party");

				server.commandAndWait("tp " + OTHER + " " + (x - 200) + " 200 " + (at[2] + 150), "Teleported " + OTHER, 120);
				waitForFar(context, x - 200, at[2] + 150, "after moving", wrong);
				photograph(context, at, "far-players-03-moved");

				server.command("execute in minecraft:the_nether run tp " + OTHER + " 0 200 0");
				waitForNone(context, "in the Nether", wrong);
				server.command("execute in minecraft:overworld run tp " + OTHER + " " + x + " 200 " + at[2]);
				waitForFar(context, x, at[2], "back in the Overworld", wrong);

				server.command("ftbteams force-remove " + party + " " + OTHER + "\nftbteams force remove " + party + " " + OTHER);
				waitForUntold(context, "out of the party", wrong);
			}
			if (opac) {
				SecondPlayer alice = (SecondPlayer) other;
				context.runOnClient(mc -> mc.getConnection().sendCommand("oparties create"));
				context.waitTicks(40);
				context.runOnClient(mc -> mc.getConnection().sendCommand("oparties member invite " + OTHER));
				// The invitation is accepted by the party's ID, which the owner's own game knows.
				String[] id = {null};
				try {
					context.waitFor(mc -> (id[0] = opacParty()) != null, 600);
				} catch (AssertionError e) {
					throw new AssertionError("Open Parties and Claims made no party after /oparties create", e);
				}
				alice.command("oparties join " + id[0]);
				waitForFar(context, x, at[2], "after joining " + me + "'s party", wrong);
				photograph(context, at, "far-players-02-opac-party");

				server.commandAndWait("tp " + OTHER + " " + (x - 200) + " 200 " + (at[2] + 150), "Teleported " + OTHER, 120);
				waitForFar(context, x - 200, at[2] + 150, "after moving", wrong);
				photograph(context, at, "far-players-03-moved");

				// Their own setting, "Share Location With Party", which Xaero's Minimap follows too.
				alice.command("opac player-config set parties.shareLocationWithParty false");
				waitForUntold(context, "not sharing their location with the party", wrong);
				alice.command("opac player-config set parties.shareLocationWithParty true");
				waitForFar(context, x - 200, at[2] + 150, "sharing their location again", wrong);
				// And the player's own, "Receive Locations From Party".
				context.runOnClient(mc -> mc.getConnection().sendCommand("opac player-config set parties.receiveLocationsFromParty false"));
				waitForUntold(context, "in the party of a player who receives no locations", wrong);
				context.runOnClient(mc -> mc.getConnection().sendCommand("opac player-config set parties.receiveLocationsFromParty true"));
				waitForFar(context, x - 200, at[2] + 150, "with locations received again", wrong);

				alice.command("oparties leave");
				waitForUntold(context, "out of the party", wrong);
			}
			leaves(other);
			RealServer.leave(context);
		}

		// Everyone, the owner's pick: no team needed.
		write(config, "far-players=everyone\n");
		try (RealServer server = RealServer.start(true)) {
			server.join(context);
			int[] at = context.computeOnClient(mc -> new int[] {mc.player.blockPosition().getX(), mc.player.blockPosition().getY(), mc.player.blockPosition().getZ()});
			AutoCloseable other = joins(server, modded);
			server.command("gamemode creative " + OTHER);
			server.commandAndWait("tp " + OTHER + " " + (at[0] + FAR) + " 200 " + at[2], "Teleported " + OTHER, 120);
			waitForFar(context, at[0] + FAR, at[2], "with far-players=everyone", wrong);
			photograph(context, at, "far-players-04-everyone");
			leaves(other);
			waitForUntold(context, "offline", wrong);
			RealServer.leave(context);
		}

		write(config, "far-players=off\n");
		try (RealServer server = RealServer.start(true)) {
			server.join(context);
			int[] at = context.computeOnClient(mc -> new int[] {mc.player.blockPosition().getX(), mc.player.blockPosition().getY(), mc.player.blockPosition().getZ()});
			AutoCloseable other = joins(server, modded);
			server.command("gamemode creative " + OTHER);
			server.commandAndWait("tp " + OTHER + " " + (at[0] + FAR) + " 200 " + at[2], "Teleported " + OTHER, 120);
			context.waitTicks(100);
			expect(wrong, far(context) == null, "with far-players=off, the map has " + OTHER + " at " + far(context));
			expect(wrong, context.computeOnClient(mc -> TrackedPlayers.fromDiorama().isEmpty()), "with far-players=off, the server says where someone is");
			leaves(other);
			RealServer.leave(context);
		}

		delete(config);
		context.runOnClient(mc -> Settings.get().playerNames = names);
		if (!wrong.isEmpty()) {
			throw new AssertionError("Players shown from afar are wrong:\n" + String.join("\n", wrong));
		}
	}

	/**
	 * The other player joins: a vanilla client, or with the server's mods a second game like this
	 * one, since a server with FTB's mods turns a vanilla client away.
	 */
	private static AutoCloseable joins(RealServer server, boolean modded) {
		if (modded) {
			return SecondPlayer.join(OTHER, server);
		}
		server.vanillaPlayerJoins(OTHER);
		return () -> server.vanillaPlayerLeaves(OTHER);
	}

	/** The ID of the party this player is in, from Open Parties and Claims in this game; null for none. */
	private static String opacParty() {
		try {
			Object api = Class.forName("xaero.pac.client.api.OpenPACClientAPI").getMethod("get").invoke(null);
			Object storage = api.getClass().getMethod("getClientPartyStorage").invoke(api);
			Object party = Class.forName("xaero.pac.client.parties.party.api.IClientPartyStorageAPI").getMethod("getParty").invoke(storage);
			return party == null ? null : Class.forName("xaero.pac.common.parties.party.api.IPartyAPI").getMethod("getId").invoke(party).toString();
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private static void leaves(AutoCloseable other) {
		try {
			other.close();
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	/** Where the map's radar would draw the other player though the client hasn't loaded them: "x, z", or null. */
	private static String far(ClientGameTestContext context) {
		return context.computeOnClient(mc -> TrackedPlayers.unloaded(mc.level).stream()
			.filter(player -> player.getScoreboardName().equals(OTHER))
			.map(player -> player.blockPosition().getX() + ", " + player.blockPosition().getZ())
			.findFirst().orElse(null));
	}

	private static void waitForFar(ClientGameTestContext context, int x, int z, String when, List<String> wrong) {
		try {
			context.waitFor(mc -> (x + ", " + z).equals(TrackedPlayers.unloaded(mc.level).stream()
				.filter(player -> player.getScoreboardName().equals(OTHER))
				.map(player -> player.blockPosition().getX() + ", " + player.blockPosition().getZ())
				.findFirst().orElse(null)), 2400);
		} catch (AssertionError e) {
			wrong.add(OTHER + " should be on the map at " + x + ", " + z + " " + when + "; the map has them at " + far(context));
		}
	}

	private static void waitForNone(ClientGameTestContext context, String why, List<String> wrong) {
		try {
			context.waitFor(mc -> TrackedPlayers.unloaded(mc.level).stream().noneMatch(player -> player.getScoreboardName().equals(OTHER)), 2400);
		} catch (AssertionError e) {
			wrong.add(OTHER + " is " + why + " and still on the map at " + far(context));
		}
	}

	/** As {@link #waitForNone}, and the server has stopped saying where anyone is: not only undrawn, but untold. */
	private static void waitForUntold(ClientGameTestContext context, String why, List<String> wrong) {
		waitForNone(context, why, wrong);
		try {
			context.waitFor(mc -> TrackedPlayers.fromDiorama().isEmpty(), 200);
		} catch (AssertionError e) {
			wrong.add(OTHER + " is " + why + " and the server still says where " + context.computeOnClient(mc -> TrackedPlayers.fromDiorama()) + " is");
		}
	}

	/** The map over both players, and whether its radar has the other one. */
	private static void photograph(ClientGameTestContext context, int[] at, String name) {
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> {
			if (mc.gui.screen() instanceof MapScreen screen) {
				screen.lookAt(Projection.u(at[0] + FAR / 2.0, at[2]), Projection.v(at[0] + FAR / 2.0, at[1], at[2]), 0.6, TileKey.SURFACE);
			}
		});
		context.waitTicks(20);
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(3);
		context.takeScreenshot(name);
		boolean drawn = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).blips().stream()
			.anyMatch(blip -> blip.kind() == Radar.Kind.PLAYER && blip.entity().getScoreboardName().equals(OTHER)));
		boolean tracked = far(context) != null;
		context.setScreen(() -> null);
		if (drawn != tracked) {
			throw new AssertionError("In " + name + " the map " + (drawn ? "draws " : "doesn't draw ") + OTHER + ", and the server " + (tracked ? "says" : "doesn't say") + " where they are");
		}
	}

	private static void expect(List<String> wrong, boolean right, String otherwise) {
		if (!right) {
			wrong.add(otherwise);
		}
	}

	private static String read(Path file) {
		try {
			return Files.readString(file);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void write(Path file, String text) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, text);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void delete(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
