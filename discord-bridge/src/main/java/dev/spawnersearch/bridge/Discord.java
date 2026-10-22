package dev.spawnersearch.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Posts and edits messages through a Discord webhook, waiting out rate limits. */
final class Discord {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

	private Discord() {}

	/** Only real Discord webhook URLs, so a typo can't send the market somewhere else. */
	static boolean validWebhook(String url) {
		return url != null && url.matches("https://(ptb\\.|canary\\.)?(discord|discordapp)\\.com/api/webhooks/\\d+/[\\w-]+");
	}

	/** Posts a message; returns its id, or null if it failed. */
	static String post(String webhook, JsonObject message) {
		HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(webhook + "?wait=true")), "POST", message);
		if (r == null || r.statusCode() / 100 != 2) return null;
		return JsonParser.parseString(r.body()).getAsJsonObject().get("id").getAsString();
	}

	/** Edits a message the webhook posted. 0 = done, 1 = it no longer exists, -1 = failed. */
	static int edit(String webhook, String id, JsonObject message) {
		HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(webhook + "/messages/" + id)), "PATCH", message);
		if (r == null) return -1;
		if (r.statusCode() == 404) return 1;
		return r.statusCode() / 100 == 2 ? 0 : -1;
	}

	private static HttpResponse<String> send(HttpRequest.Builder b, String method, JsonObject message) {
		HttpRequest req = b.timeout(Duration.ofSeconds(20))
				.header("Content-Type", "application/json")
				.header("User-Agent", "SpawnerSearchBridge/1.0")
				.method(method, HttpRequest.BodyPublishers.ofString(message.toString()))
				.build();
		for (int attempt = 0; attempt < 4; attempt++) {
			try {
				HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
				if (r.statusCode() == 429) {
					// Rate limited: Discord says how long to wait.
					double wait = 2;
					try {
						wait = JsonParser.parseString(r.body()).getAsJsonObject().get("retry_after").getAsDouble();
					} catch (Exception ignored) {
						// keep the default
					}
					Thread.sleep((long) (Math.min(60, wait) * 1000) + 250);
					continue;
				}
				if (r.statusCode() / 100 != 2 && r.statusCode() != 404) {
					Bridge.log("Discord antwortet " + r.statusCode() + ": " + r.body());
				}
				return r;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			} catch (Exception e) {
				Bridge.log("Discord nicht erreichbar: " + e.getMessage());
				try {
					Thread.sleep(3000);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					return null;
				}
			}
		}
		return null;
	}
}
