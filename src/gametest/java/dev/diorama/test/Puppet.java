package dev.diorama.test;

//? if fabric {
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;

/**
 * The part of a {@link SecondPlayer} that runs in its game: while the game was started with a
 * commands file, each line added to that file is sent as a command once the player is on a
 * server. In any other game, the tests' own included, it does nothing.
 */
public final class Puppet implements ClientModInitializer {
	static final String PROPERTY = "diorama.test.puppet";

	@Override
	public void onInitializeClient() {
		String file = System.getProperty(PROPERTY);
		if (file == null) {
			return;
		}
		Path commands = Path.of(file);
		Thread reader = new Thread(() -> {
			int sent = 0;
			while (true) {
				try {
					Thread.sleep(250);
					List<String> lines = Files.readAllLines(commands);
					Minecraft mc = Minecraft.getInstance();
					while (sent < lines.size() && mc.getConnection() != null && mc.player != null) {
						String command = lines.get(sent++);
						mc.execute(() -> {
							if (mc.getConnection() != null) {
								mc.getConnection().sendCommand(command);
							}
						});
					}
				} catch (IOException e) {
					// Being written: read again.
				} catch (InterruptedException e) {
					return;
				}
			}
		}, "diorama-test-puppet");
		reader.setDaemon(true);
		reader.start();
	}
}
//?} else {
/*final class Puppet {
	static final String PROPERTY = "diorama.test.puppet";
}
*///?}
