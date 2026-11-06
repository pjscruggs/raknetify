package com.ishland.buildscript;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Formats commit summaries from the GitHub push-event payload for development publication metadata.
 */
public class ParseGItHubActionChangelog {

    /**
     * Reads the event payload selected by GITHUB_EVENT_RAW_PATH and formats linked commit
     * summaries. Missing event-path configuration produces a short fallback instead of reading an
     * arbitrary file.
     *
     * @return Markdown changelog containing each commit subject and author, or the unconfigured
     *     fallback
     * @throws Throwable if the configured payload cannot be read or does not match the expected
     *     push-event structure
     */
    public static String getChangelog() throws Throwable {
        final String path = System.getenv("GITHUB_EVENT_RAW_PATH");
        if (path == null || path.isBlank()) {
            return "No changelog was specified. ";
        }
        final JsonObject jsonObject =
                new Gson().fromJson(Files.readString(Path.of(path)), JsonObject.class);

        StringBuilder builder = new StringBuilder();
        builder.append(
                "This version is uploaded automatically by GitHub Actions.  \n\nChangelog:  \n");
        final JsonArray commits = jsonObject.getAsJsonArray("commits");
        if (commits.isEmpty()) {
            builder.append("No changes detected. \n");
        } else {
            for (JsonElement commit : commits) {
                JsonObject object = commit.getAsJsonObject();
                builder.append("- [")
                        .append(object.get("id").getAsString(), 0, 8)
                        .append("](")
                        .append(object.get("url").getAsString())
                        .append(") ")
                        .append(object.get("message").getAsString().split("\n")[0])
                        .append(" - ")
                        .append(object.get("author").getAsJsonObject().get("name").getAsString())
                        .append("  \n");
            }
        }
        return builder.toString();
    }
}
