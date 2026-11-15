package dev.diorama.web;

import dev.diorama.Diorama;
import dev.diorama.Facing;
import dev.diorama.Projection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The players on the server's web map, as BlueMap, Dynmap and squaremap show theirs: once a
 * second, who is online and where, written to {@code players.json} beside the map's pictures, so
 * the page shows them whether Diorama's own web server or the owner's serves the folder.
 *
 * <p>Left out, so the page says no more than a player near them in the game could see: anyone
 * sneaking (the game hides their name behind blocks then, and it's how a player takes themselves
 * off the page), invisible or spectating, and anyone in a dimension the map leaves out.
 */
final class WebPlayers {
	static final String FILE = "players.json";
	private static final String NONE = "{\"players\":[]}";
	private static final long EVERY_MS = 1000;

	private final MinecraftServer server;
	private final Path folder;
	private final Predicate<ServerLevel> leftOut;
	private final Thread thread;
	private volatile boolean stopping;
	private boolean warned;

	WebPlayers(MinecraftServer server, Path folder, Predicate<ServerLevel> leftOut) {
		this.server = server;
		this.folder = folder;
		this.leftOut = leftOut;
		this.thread = new Thread(this::run, "Diorama web map players");
		this.thread.setDaemon(true);
	}

	void start() {
		this.thread.start();
	}

	/** Stops, leaving a file that names no one: a page served from the folder shows nobody while the server is down. */
	void stop() {
		this.stopping = true;
		this.thread.interrupt();
		try {
			this.thread.join(5_000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		this.write(NONE);
	}

	/** Takes away the file a web map with players on it left, now that they're off it. */
	static void remove(Path folder) {
		try {
			Files.deleteIfExists(folder.resolve(FILE));
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't delete {} from the web map's folder, which still says where players were; delete it yourself", FILE, e);
		}
	}

	private void run() {
		// Before the first look: no one from the last time the server ran.
		String written = NONE;
		this.write(NONE);
		while (!this.stopping) {
			try {
				Thread.sleep(EVERY_MS);
				// Entities are the server thread's; it answers between ticks.
				CompletableFuture<String> asked = new CompletableFuture<>();
				this.server.execute(() -> {
					try {
						asked.complete(this.collect());
					} catch (RuntimeException e) {
						asked.completeExceptionally(e);
					}
				});
				String now = asked.get(10, TimeUnit.SECONDS);
				if (!now.equals(written) && !this.stopping && this.write(now)) {
					written = now;
				}
			} catch (InterruptedException e) {
				return;
			} catch (TimeoutException e) {
				// A server too far behind to answer: the page keeps what it has until it does.
			} catch (ExecutionException e) {
				if (!this.warned) {
					this.warned = true;
					Diorama.LOGGER.warn("The web map couldn't read where players are", e.getCause());
				}
			}
		}
	}

	/** Who the page shows, as its JSON. Server thread. */
	private String collect() {
		List<ServerPlayer> shown = new ArrayList<>();
		for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
			if (player.isAlive() && !player.isSpectator() && !player.isInvisible() && !player.isShiftKeyDown()
				&& player.level() instanceof ServerLevel level && !this.leftOut.test(level)) {
				shown.add(player);
			}
		}
		// By name, so the file only changes when someone moves.
		shown.sort(Comparator.comparing(ServerPlayer::getScoreboardName));
		Facing facing = Projection.facing();
		StringBuilder json = new StringBuilder("{\"players\":[");
		for (int i = 0; i < shown.size(); i++) {
			ServerPlayer player = shown.get(i);
			// Which way they look, across the picture: yaw 0 is south, turning clockwise from above.
			double yaw = Math.toRadians(player.getYRot());
			double lookX = -Math.sin(yaw);
			double lookZ = Math.cos(yaw);
			double heading = Math.atan2(facing.z(lookX, lookZ) * Projection.sin(), facing.x(lookX, lookZ));
			json.append(i == 0 ? "" : ",").append(String.format(Locale.ROOT,
				"{\"name\":\"%s\",\"dimension\":\"%s\",\"x\":%d,\"y\":%d,\"z\":%d,\"u\":%.1f,\"v\":%.1f,\"heading\":%.2f}",
				escape(player.getScoreboardName()), escape(player.level().dimension().identifier().toString()),
				player.blockPosition().getX(), player.blockPosition().getY(), player.blockPosition().getZ(),
				Projection.u(player.getX(), player.getZ()), Projection.v(player.getX(), player.getY(), player.getZ()), heading));
		}
		return json.append("]}").toString();
	}

	private boolean write(String json) {
		Path file = this.folder.resolve(FILE);
		Path part = this.folder.resolve(FILE + ".part");
		try {
			Files.writeString(part, json, StandardCharsets.UTF_8);
			Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			return true;
		} catch (IOException e) {
			if (!this.warned) {
				this.warned = true;
				Diorama.LOGGER.warn("Couldn't write the web map's {}; the page shows no players until it can", FILE, e);
			}
			return false;
		}
	}

	private static String escape(String text) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '"' || c == '\\') {
				out.append('\\').append(c);
			} else if (c < 0x20 || c > 0x7E) {
				out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}
}
