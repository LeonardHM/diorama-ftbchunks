package dev.diorama.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.diorama.Diorama;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * The markers an operator placed on the server's web map, as BlueMap's and Dynmap's marker
 * commands place theirs: named places everyone sees on the page (spawn, a shop, a nether hub).
 * They aren't players' waypoints, which stay on each player's own computer.
 *
 * <p>Kept in {@code diorama-web-markers.json} in the world folder, which the server alone reads.
 * What the page reads is {@code markers.json} beside the map's pictures, written from it without
 * the markers of dimensions the map leaves out: a marker's name and place are meant to be public,
 * but not in a dimension the owner has since turned the map off in.
 */
final class WebMarkers {
	static final String FILE = "markers.json";
	static final int MOST = 500;
	static final int LONGEST_NAME = 48;

	/** A named place. */
	record Marker(String name, String dimension, int x, int y, int z) {
	}

	private final Path store;
	private final Path folder;
	private final List<Marker> markers = new ArrayList<>();

	WebMarkers(Path store, Path folder) {
		this.store = store;
		this.folder = folder;
		if (Files.isRegularFile(store)) {
			try {
				JsonObject json = JsonParser.parseString(Files.readString(store, StandardCharsets.UTF_8)).getAsJsonObject();
				for (JsonElement element : json.getAsJsonArray("markers")) {
					JsonObject marker = element.getAsJsonObject();
					this.markers.add(new Marker(marker.get("name").getAsString(), marker.get("dimension").getAsString(),
						marker.get("x").getAsInt(), marker.get("y").getAsInt(), marker.get("z").getAsInt()));
				}
			} catch (IOException | RuntimeException e) {
				Diorama.LOGGER.warn("Couldn't read the web map's markers in {}; it stays as it is, and the page shows none until it's mended", store, e);
				this.markers.clear();
				this.unreadable = true;
			}
		}
	}

	/** The store couldn't be read: nothing is written over it. */
	private boolean unreadable;

	synchronized List<Marker> all() {
		return List.copyOf(this.markers);
	}

	/** Whether markers can be changed: not while a store that couldn't be read would be written over. */
	synchronized boolean writable() {
		return !this.unreadable;
	}

	/** The marker with this name, whatever its case, or null. */
	synchronized @Nullable Marker named(String name) {
		for (Marker marker : this.markers) {
			if (marker.name().equalsIgnoreCase(name)) {
				return marker;
			}
		}
		return null;
	}

	/** Places a marker, moving the one of that name if there is one. False if it couldn't be saved; nothing changes then. */
	synchronized boolean put(Marker marker, Predicate<String> leftOut) {
		List<Marker> before = List.copyOf(this.markers);
		this.markers.removeIf(m -> m.name().equalsIgnoreCase(marker.name()));
		this.markers.add(marker);
		return this.saved(before, leftOut);
	}

	/** Takes a marker away. False if it couldn't be saved; nothing changes then. */
	synchronized boolean remove(Marker marker, Predicate<String> leftOut) {
		List<Marker> before = List.copyOf(this.markers);
		this.markers.remove(marker);
		return this.saved(before, leftOut);
	}

	private boolean saved(List<Marker> before, Predicate<String> leftOut) {
		try {
			write(this.store, this.markers);
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't save the web map's markers in {}", this.store, e);
			this.markers.clear();
			this.markers.addAll(before);
			return false;
		}
		this.publish(leftOut);
		return true;
	}

	/** Writes what the page reads: every marker but those in a dimension {@code leftOut} says yes to. */
	synchronized void publish(Predicate<String> leftOut) {
		List<Marker> shown = this.markers.stream().filter(marker -> !leftOut.test(marker.dimension())).toList();
		try {
			write(this.folder.resolve(FILE), shown);
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't write the web map's {}; the page shows the markers it had", FILE, e);
		}
	}

	private static void write(Path file, List<Marker> markers) throws IOException {
		JsonArray list = new JsonArray();
		for (Marker marker : markers) {
			JsonObject json = new JsonObject();
			json.addProperty("name", marker.name());
			json.addProperty("dimension", marker.dimension());
			json.addProperty("x", marker.x());
			json.addProperty("y", marker.y());
			json.addProperty("z", marker.z());
			list.add(json);
		}
		JsonObject json = new JsonObject();
		json.add("markers", list);
		Path part = file.resolveSibling(file.getFileName() + ".part");
		Files.writeString(part, json.toString(), StandardCharsets.UTF_8);
		Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
