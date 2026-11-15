package dev.diorama.web;

import dev.diorama.Diorama;
import dev.diorama.Facing;
import dev.diorama.Projection;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.stream.Stream;
//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
//?} else {
/*import net.neoforged.fml.ModList;
*///?}
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Land claims on the server's web map: FTB Chunks' and Open Parties and Claims', with their
 * owners' names and colours, as the map in the game shows them. Every couple of seconds the server
 * thread reads them from each mod's own server API, and when they changed {@code claims.json} is
 * written beside the pictures for the page. Which chunk each picture's pixel shows is written with
 * the picture ({@link WebChunks}), so the page paints a claim on the ground it covers, hills too.
 *
 * <p>The page shows only what every player in the game is shown: FTB Chunks hides a team's claims
 * from other teams unless the team's claim visibility is public, and Open Parties and Claims sends
 * other players' claims only while its {@code claimsSynchronization} is {@code ALL}. Dimensions the
 * web map leaves out ({@link WebMap#leftOut}) have none.
 *
 * <p>Diorama doesn't build against either mod: their server APIs are read by reflection. A name
 * that isn't where this class expects it turns that mod's claims off, logged once.
 */
final class WebClaims {
	static final String FILE = "claims.json";
	/** The least time between two reads. */
	private static final long MIN_GAP_MS = 2000;
	/** A read may take this share of the server thread's time at most: the next waits this many times as long as it took. */
	private static final int BUDGET = 200;

	private final MinecraftServer server;
	private final Predicate<ServerLevel> leftOut;
	private final Path folder;
	private final List<Source> sources;
	private final Thread thread;
	private volatile boolean stopping;
	/** What the file holds now; null before it's written. */
	private @Nullable String written;

	private WebClaims(MinecraftServer server, Predicate<ServerLevel> leftOut, Path folder, List<Source> sources) {
		this.server = server;
		this.leftOut = leftOut;
		this.folder = folder;
		this.sources = sources;
		this.thread = new Thread(this::run, "Diorama web map claims");
		this.thread.setDaemon(true);
	}

	/** The claims to show, or null when no claims mod this reads is installed. */
	static @Nullable WebClaims of(MinecraftServer server, Predicate<ServerLevel> leftOut, Path folder) {
		List<Source> sources = new ArrayList<>();
		if (loaded("ftbchunks")) {
			sources.add(new FtbChunks());
		}
		if (loaded("openpartiesandclaims")) {
			sources.add(new OpenPartiesAndClaims());
		}
		return sources.isEmpty() ? null : new WebClaims(server, leftOut, folder, sources);
	}

	/** Whether a claims mod this reads is installed, so its claims can be shown. */
	static boolean anyMod() {
		return loaded("ftbchunks") || loaded("openpartiesandclaims");
	}

	private static boolean loaded(String id) {
		//? if fabric {
		return FabricLoader.getInstance().isModLoaded(id);
		//?} else {
		/*return ModList.get().isLoaded(id);
		*///?}
	}

	/** Deletes the claims file left in a map's folder, so a page can't show claims that aren't current or allowed. */
	static void delete(Path folder) {
		try {
			Files.deleteIfExists(folder.resolve(FILE));
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't delete the web map's old {} in {}", FILE, folder, e);
		}
	}

	void start() {
		this.thread.start();
	}

	void stop() {
		this.stopping = true;
		this.thread.interrupt();
	}

	private void run() {
		CompletableFuture<Read> asked = null;
		long wait = 0;
		while (!this.stopping) {
			try {
				Thread.sleep(wait);
				if (asked == null) {
					// The claims mods keep their claims on the server thread; it reads them between ticks.
					CompletableFuture<Read> look = new CompletableFuture<>();
					asked = look;
					this.server.execute(() -> {
						try {
							look.complete(this.read());
						} catch (RuntimeException | LinkageError e) {
							look.completeExceptionally(e);
						}
					});
				}
				Read read = asked.get(MIN_GAP_MS, TimeUnit.MILLISECONDS);
				asked = null;
				wait = Math.max(MIN_GAP_MS, read.nanos() * BUDGET / 1_000_000);
				if (read.complete() && !this.stopping) {
					this.write(read);
				}
			} catch (InterruptedException e) {
				return;
			} catch (TimeoutException e) {
				// A server too far behind to answer yet: waited for again.
				wait = 0;
			} catch (ExecutionException e) {
				asked = null;
				wait = MIN_GAP_MS;
				Diorama.LOGGER.debug("The web map couldn't read land claims", e.getCause());
			}
		}
	}

	/** One claim's owner as the page shows it. {@code titled}: {@code name} is the claim's whole title, as Open Parties and Claims words it. */
	private record Owner(String name, int rgb, boolean forced, boolean titled) {
	}

	/** Everyone's claims at one moment, by dimension: chunk (world x, z) to owner. {@code complete} false while a mod can't say yet. */
	private record Read(Map<String, Map<Long, Owner>> dimensions, boolean complete, long nanos) {
	}

	/** Reads every claim the page may show. Server thread. */
	private Read read() {
		long start = System.nanoTime();
		Map<String, Boolean> shown = new HashMap<>();
		for (ServerLevel level : this.server.getAllLevels()) {
			shown.put(level.dimension().identifier().toString(), !this.leftOut.test(level));
		}
		Map<String, Map<Long, Owner>> dimensions = new TreeMap<>();
		boolean complete = true;
		for (Source source : this.sources) {
			complete &= source.read(this.server, (dimension, x, z, owner) -> {
				if (shown.getOrDefault(dimension, false)) {
					// A chunk both mods claim, which shouldn't happen: the first one's.
					dimensions.computeIfAbsent(dimension, d -> new HashMap<>()).putIfAbsent(key(x, z), owner);
				}
			});
		}
		return new Read(dimensions, complete, System.nanoTime() - start);
	}

	private static long key(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	/** Writes the file when the claims differ from what it holds. */
	private void write(Read read) {
		Facing facing = Projection.facing();
		StringBuilder json = new StringBuilder("{\"dimensions\":[");
		boolean firstDimension = true;
		for (Map.Entry<String, Map<Long, Owner>> dimension : read.dimensions().entrySet()) {
			// Chunks in the pictures' turned space, in order, so the same claims always read the same.
			TreeMap<Long, Owner> chunks = new TreeMap<>();
			for (Map.Entry<Long, Owner> chunk : dimension.getValue().entrySet()) {
				int x = (int) (chunk.getKey() >> 32);
				int z = (int) (long) chunk.getKey();
				chunks.put(key(facing.blockX(x, z), facing.blockZ(x, z)), chunk.getValue());
			}
			Map<Owner, Integer> owners = new HashMap<>();
			List<Owner> order = new ArrayList<>();
			StringBuilder list = new StringBuilder();
			for (Map.Entry<Long, Owner> chunk : chunks.entrySet()) {
				Integer index = owners.get(chunk.getValue());
				if (index == null) {
					index = order.size();
					owners.put(chunk.getValue(), index);
					order.add(chunk.getValue());
				}
				list.append(list.isEmpty() ? "" : ",").append((int) (chunk.getKey() >> 32)).append(',').append((int) (long) chunk.getKey()).append(',').append(index);
			}
			json.append(firstDimension ? "" : ",").append("{\"id\":\"").append(escape(dimension.getKey())).append("\",\"owners\":[");
			firstDimension = false;
			for (int i = 0; i < order.size(); i++) {
				Owner owner = order.get(i);
				json.append(i == 0 ? "" : ",").append("{\"name\":\"").append(escape(owner.name()))
					.append(String.format(Locale.ROOT, "\",\"color\":\"#%06x\"", owner.rgb() & 0xFFFFFF))
					.append(owner.forced() ? ",\"forced\":true" : "").append(owner.titled() ? ",\"titled\":true" : "").append('}');
			}
			json.append("],\"chunks\":[").append(list).append("]}");
		}
		json.append("]}");
		String text = json.toString();
		if (text.equals(this.written)) {
			return;
		}
		Path file = this.folder.resolve(FILE);
		Path part = this.folder.resolve(FILE + ".part");
		try {
			Files.writeString(part, text, StandardCharsets.UTF_8);
			Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			this.written = text;
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't write the web map's {}", FILE, e);
		}
	}

	static String escape(String text) {
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

	/** Where each claim goes as a mod reads it. */
	private interface Found {
		void claim(String dimension, int chunkX, int chunkZ, Owner owner);
	}

	/** One claims mod's claims, read through its server API. */
	private abstract static class Source {
		private boolean looked;
		private boolean failed;

		/** Hands every claim others may see to {@code found}; false when the mod can't say now. Server thread. */
		final boolean read(MinecraftServer server, Found found) {
			if (!this.looked) {
				this.looked = true;
				try {
					this.lookUp(server);
					Diorama.LOGGER.info("{} is installed: the web map shows its land claims", this.name());
				} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
					this.fail(e);
				}
			}
			if (this.failed) {
				return true;
			}
			try {
				return this.claims(server, found);
			} catch (InvocationTargetException e) {
				// The mod itself threw: tried again next time.
				Diorama.LOGGER.debug("{} couldn't give its claims", this.name(), e);
				return false;
			} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
				this.fail(e);
				return true;
			}
		}

		private void fail(Throwable e) {
			if (!this.failed) {
				this.failed = true;
				Diorama.LOGGER.warn("Couldn't read {}'s land claims; the web map won't show them", this.name(), e);
			}
		}

		abstract String name();

		abstract void lookUp(MinecraftServer server) throws ReflectiveOperationException;

		abstract boolean claims(MinecraftServer server, Found found) throws ReflectiveOperationException;
	}

	/**
	 * FTB Chunks: every claimed chunk from its manager, and each team's name and colour from FTB
	 * Teams, as the map in the game shows them. A team whose claim visibility isn't public has its
	 * claims sent only to its allies, so the page leaves them out. The same names from 1.20.1 to 26.1.
	 */
	private static final class FtbChunks extends Source {
		private Object api;
		private Method managerLoaded;
		private Method manager;
		private Method allChunks;
		private Method chunkPos;
		private Method chunkTeamData;
		private Method chunkForceLoaded;
		private Method posDimension;
		private Method posX;
		private Method posZ;
		private Method hidden;
		private Method team;
		private Method getProperty;
		private Object colorProperty;
		private Object nameProperty;
		private Method rgb;

		@Override
		String name() {
			return "FTB Chunks";
		}

		@Override
		void lookUp(MinecraftServer server) throws ReflectiveOperationException {
			String api = "dev.ftb.mods.ftbchunks.api.";
			this.api = Class.forName(api + "FTBChunksAPI").getMethod("api").invoke(null);
			Class<?> apiType = Class.forName(api + "FTBChunksAPI$API");
			this.managerLoaded = apiType.getMethod("isManagerLoaded");
			this.manager = apiType.getMethod("getManager");
			this.allChunks = Class.forName(api + "ClaimedChunkManager").getMethod("getAllClaimedChunks");
			Class<?> chunk = Class.forName(api + "ClaimedChunk");
			this.chunkPos = chunk.getMethod("getPos");
			this.chunkTeamData = chunk.getMethod("getTeamData");
			this.chunkForceLoaded = chunk.getMethod("isForceLoaded");
			Class<?> pos = Class.forName("dev.ftb.mods.ftblibrary.math.ChunkDimPos");
			this.posDimension = pos.getMethod("dimension");
			this.posX = pos.getMethod("x");
			this.posZ = pos.getMethod("z");
			Class<?> teamData = Class.forName(api + "ChunkTeamData");
			this.hidden = teamData.getMethod("shouldHideClaims");
			this.team = teamData.getMethod("getTeam");
			Class<?> team = Class.forName("dev.ftb.mods.ftbteams.api.Team");
			this.getProperty = team.getMethod("getProperty", Class.forName("dev.ftb.mods.ftbteams.api.property.TeamProperty"));
			Class<?> properties = Class.forName("dev.ftb.mods.ftbteams.api.property.TeamProperties");
			this.colorProperty = properties.getField("COLOR").get(null);
			this.nameProperty = properties.getField("DISPLAY_NAME").get(null);
			this.rgb = Class.forName("dev.ftb.mods.ftblibrary.icon.Color4I").getMethod("rgb");
		}

		@Override
		boolean claims(MinecraftServer server, Found found) throws ReflectiveOperationException {
			if (!(boolean) this.managerLoaded.invoke(this.api)) {
				return false;
			}
			Collection<?> chunks = (Collection<?>) this.allChunks.invoke(this.manager.invoke(this.api));
			// A team's owner looked up once a read: a team owns many chunks.
			Map<Object, Optional<Owner>> teams = new IdentityHashMap<>();
			for (Object chunk : chunks) {
				Object data = this.chunkTeamData.invoke(chunk);
				Optional<Owner> owner = teams.get(data);
				if (owner == null) {
					owner = this.owner(data);
					teams.put(data, owner);
				}
				if (owner.isEmpty()) {
					continue;
				}
				Object pos = this.chunkPos.invoke(chunk);
				Owner claim = owner.get();
				if ((boolean) this.chunkForceLoaded.invoke(chunk)) {
					claim = new Owner(claim.name(), claim.rgb(), true, false);
				}
				found.claim(((net.minecraft.resources.ResourceKey<?>) this.posDimension.invoke(pos)).identifier().toString(),
					(int) this.posX.invoke(pos), (int) this.posZ.invoke(pos), claim);
			}
			return true;
		}

		/** A team's claims as the page shows them; empty when only its allies see them. */
		private Optional<Owner> owner(Object data) throws ReflectiveOperationException {
			if ((boolean) this.hidden.invoke(data)) {
				return Optional.empty();
			}
			Object team = this.team.invoke(data);
			Object colour = this.getProperty.invoke(team, this.colorProperty);
			Object name = this.getProperty.invoke(team, this.nameProperty);
			return Optional.of(new Owner(name instanceof String s ? s : "", colour == null ? 0xFFFFFF : (int) this.rgb.invoke(colour), false, false));
		}
	}

	/**
	 * Open Parties and Claims: every claim from its server claims manager, region by region, with the
	 * title and colour it gives a claim ("Base - Alex's Claim"), in the server's own language as it
	 * words them for players without the mod. Older versions (the newest on 1.21.3, 1.21.6 and
	 * 1.21.9) have no call for a title or colour, so they're put together here as those word them.
	 */
	private static final class OpenPartiesAndClaims extends Source {
		private static final String PAC = "xaero.pac.";
		private static final UUID SERVER_CLAIM = new UUID(0, 0);
		private static final UUID EXPIRED_CLAIM = new UUID(0, 1);

		private Method serverApi;
		private Method claimsManager;
		private Method localizer;
		private Method localize;
		private Method localizeKey;
		private Method dimensionStream;
		private Method dimensionId;
		private Method regionStream;
		private Method regionX;
		private Method regionZ;
		private Method regionClaim;
		private Method claimPlayer;
		private Method claimSub;
		private Method claimForceloadable;
		private @Nullable Method fullName;
		private @Nullable Method colour;
		private Method playerInfo;
		private Method username;
		private Method claimsName;
		private Method subClaimsName;
		private Method claimsColour;
		private Method subClaimsColour;
		private Object config;
		private java.lang.reflect.Field syncField;

		@Override
		String name() {
			return "Open Parties and Claims";
		}

		@Override
		void lookUp(MinecraftServer server) throws ReflectiveOperationException {
			Class<?> api = Class.forName(PAC + "common.server.api.OpenPACServerAPI");
			this.serverApi = api.getMethod("get", MinecraftServer.class);
			this.claimsManager = api.getMethod("getServerClaimsManager");
			this.localizer = api.getMethod("getAdaptiveTextLocalizer");
			Class<?> localizerType = Class.forName(PAC + "common.server.player.localization.api.IAdaptiveLocalizerAPI");
			this.localize = localizerType.getMethod("getFor", net.minecraft.server.level.ServerPlayer.class, Component.class);
			this.localizeKey = localizerType.getMethod("getFor", net.minecraft.server.level.ServerPlayer.class, String.class, Object[].class);
			Class<?> manager = Class.forName(PAC + "common.server.claims.api.IServerClaimsManagerAPI");
			Class<?> claim = Class.forName(PAC + "common.claims.player.api.IPlayerChunkClaimAPI");
			this.dimensionStream = manager.getMethod("getDimensionStream");
			Class<?> dimension = Class.forName(PAC + "common.server.claims.api.IServerDimensionClaimsManagerAPI");
			this.dimensionId = dimension.getMethod("getDimension");
			this.regionStream = dimension.getMethod("getRegionStream");
			Class<?> region = Class.forName(PAC + "common.server.claims.api.IServerRegionClaimsAPI");
			this.regionX = region.getMethod("getX");
			this.regionZ = region.getMethod("getZ");
			this.regionClaim = region.getMethod("get", int.class, int.class);
			this.claimPlayer = claim.getMethod("getPlayerId");
			this.claimSub = claim.getMethod("getSubConfigIndex");
			this.claimForceloadable = claim.getMethod("isForceloadable");
			try {
				this.fullName = manager.getMethod("getFullName", claim, net.minecraft.resources.Identifier.class, boolean.class);
				this.colour = manager.getMethod("getColor", claim, net.minecraft.resources.Identifier.class);
			} catch (NoSuchMethodException e) {
				// Older versions.
				this.fullName = null;
				this.colour = null;
			}
			this.playerInfo = manager.getMethod("getPlayerInfo", UUID.class);
			Class<?> info = Class.forName(PAC + "common.server.claims.player.api.IServerPlayerClaimInfoAPI");
			this.username = info.getMethod("getPlayerUsername");
			this.claimsName = info.getMethod("getClaimsName");
			this.subClaimsName = info.getMethod("getClaimsName", int.class);
			this.claimsColour = info.getMethod("getClaimsColor");
			this.subClaimsColour = info.getMethod("getClaimsColor", int.class);
			// Whether other players' claims reach the game at all: not its API, but the only place it's kept.
			Class<?> config = Class.forName(PAC + "common.server.config.ServerConfig");
			this.config = config.getField("CONFIG").get(null);
			this.syncField = config.getField("claimsSynchronization");
		}

		@Override
		boolean claims(MinecraftServer server, Found found) throws ReflectiveOperationException {
			// ALL sends every claim to every player; OWNED_ONLY each player their own and the server's;
			// NOT_SYNCED none but their own. The page is no one's, so it shows what reaches everyone.
			Object sync = this.syncField.get(this.config);
			String mode = String.valueOf(sync.getClass().getMethod("get").invoke(sync));
			if (mode.equals("NOT_SYNCED")) {
				return true;
			}
			boolean all = mode.equals("ALL");
			Object api = this.serverApi.invoke(null, server);
			Object manager = this.claimsManager.invoke(api);
			Object localizer = this.localizer.invoke(api);
			try (Stream<?> dimensions = (Stream<?>) this.dimensionStream.invoke(manager)) {
				for (Iterator<?> d = dimensions.iterator(); d.hasNext(); ) {
					Object dimension = d.next();
					Object id = this.dimensionId.invoke(dimension);
					String name = id.toString();
					// Open Parties and Claims keeps one claim object per kind of claim, so a dimension has few.
					Map<Object, Optional<Owner>> kinds = new IdentityHashMap<>();
					try (Stream<?> regions = (Stream<?>) this.regionStream.invoke(dimension)) {
						for (Iterator<?> r = regions.iterator(); r.hasNext(); ) {
							Object region = r.next();
							int rx = (int) this.regionX.invoke(region);
							int rz = (int) this.regionZ.invoke(region);
							for (int i = 0; i < 32; i++) {
								for (int j = 0; j < 32; j++) {
									Object state = this.regionClaim.invoke(region, i, j);
									if (state == null) {
										continue;
									}
									Optional<Owner> owner = kinds.get(state);
									if (owner == null) {
										boolean everyone = all || SERVER_CLAIM.equals(this.claimPlayer.invoke(state));
										owner = everyone ? Optional.of(this.owner(manager, localizer, state, id)) : Optional.empty();
										kinds.put(state, owner);
									}
									if (owner.isPresent()) {
										found.claim(name, rx << 5 | i, rz << 5 | j, owner.get());
									}
								}
							}
						}
					}
				}
			}
			return true;
		}

		private Owner owner(Object manager, Object localizer, Object state, Object dimension) throws ReflectiveOperationException {
			boolean forceloadable = (boolean) this.claimForceloadable.invoke(state);
			if (this.fullName != null) {
				Component title = (Component) this.localize.invoke(localizer, null, this.fullName.invoke(manager, state, dimension, true));
				return new Owner(title.getString(), (int) this.colour.invoke(manager, state, dimension) & 0xFFFFFF, forceloadable, true);
			}
			UUID player = (UUID) this.claimPlayer.invoke(state);
			int sub = (int) this.claimSub.invoke(state);
			Object info = this.playerInfo.invoke(manager, player);
			String custom = (String) this.subClaimsName.invoke(info, sub);
			if (sub != -1 && (custom == null || custom.isEmpty())) {
				custom = (String) this.claimsName.invoke(info);
			}
			Component forceloaded = forceloadable ? this.text(localizer, "gui.xaero_pac_marked_for_forceload") : Component.literal("");
			Component owner = player.equals(SERVER_CLAIM) ? this.text(localizer, "gui.xaero_pac_title_entered_server_claim", forceloaded)
				: player.equals(EXPIRED_CLAIM) ? this.text(localizer, "gui.xaero_pac_title_entered_expired_claim", forceloaded)
				: this.text(localizer, "gui.xaero_pac_title_entered_claim", (String) this.username.invoke(info), forceloaded);
			String title = custom == null || custom.isEmpty() ? owner.getString() : custom + " - " + owner.getString();
			Integer own = (Integer) this.subClaimsColour.invoke(info, sub);
			return new Owner(title, (own != null ? own : (int) this.claimsColour.invoke(info)) & 0xFFFFFF, forceloadable, true);
		}

		private Component text(Object localizer, String key, Object... args) throws ReflectiveOperationException {
			return (Component) this.localizeKey.invoke(localizer, null, key, args);
		}
	}
}
