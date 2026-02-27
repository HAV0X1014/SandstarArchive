package katworks.web;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.staticfiles.Location;
import katworks.database.DatabaseHandler;
import katworks.impl.ArtistDetails;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static katworks.Main.config;

public class WebServer {

    public static void start() {
        Javalin app = Javalin.create(figgy -> {
            figgy.staticFiles.add("public/", Location.EXTERNAL);
            figgy.staticFiles.add(staticFiles -> {
                staticFiles.directory = config.imageDownloadPath;
                staticFiles.location = Location.EXTERNAL;
                staticFiles.hostedPath = "/images";
            });
        }).start(config.port);

        // --- API ROUTES ---
        app.get("/api/posts/global", ctx -> {
            int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(20);
            int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);
            List<String> contentFilters = ctx.queryParams("c");
            List<String> safetyFilters = ctx.queryParams("s");
            String sort = ctx.queryParam("sort");
            ctx.json(DatabaseHandler.getGlobalPostsPaged(limit, offset, contentFilters, safetyFilters, sort));
        });

        app.get("/api/artists/search", ctx -> ctx.json(DatabaseHandler.searchArtists(ctx.queryParam("q"))));
        app.get("/api/accounts/search", ctx -> ctx.json(DatabaseHandler.searchAccounts(ctx.queryParam("q"))));
        app.get("/api/accounts/active", ctx -> ctx.json(DatabaseHandler.getActiveAccounts()));
        app.get("/api/accounts", ctx -> ctx.json(DatabaseHandler.getAllAccounts()));

        app.get("/api/artists/name/{name}", ctx -> {
            ArtistDetails details = DatabaseHandler.getArtistDetailsByName(ctx.pathParam("name"));
            if (details == null) ctx.status(404);
            else ctx.json(details);
        });

        app.get("/api/artists/{id}", ctx -> ctx.json(DatabaseHandler.getArtistDetails(Integer.parseInt(ctx.pathParam("id")))));
        app.get("/api/accounts/{id}", ctx -> ctx.json(DatabaseHandler.getAccountById(ctx.pathParam("id"))));

        app.get("/api/posts/{twitterId}", ctx -> {
            String twitterId = ctx.pathParam("twitterId");
            int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(20);
            int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);
            List<String> contentFilters = ctx.queryParams("c");
            List<String> safetyFilters = ctx.queryParams("s");
            String sort = ctx.queryParam("sort");
            ctx.json(DatabaseHandler.getPostsByUserIdPaged(twitterId, limit, offset, contentFilters, safetyFilters, sort));
        });

        app.get("/api/post/{id}", ctx -> ctx.json(DatabaseHandler.getPostDetails(ctx.pathParam("id"))));
        app.get("/api/media/{id}", ctx -> ctx.json(DatabaseHandler.getMediaById(Integer.parseInt(ctx.pathParam("id")))));

        app.get("/api/config", ctx -> {
            List<String> safetyRatings = new ArrayList<>(config.safetyRatings);
            List<String> contentRatings = new ArrayList<>(config.contentRatings);
            safetyRatings.remove("Waiting");
            contentRatings.remove("Waiting");
            ctx.json(Map.of("safety", safetyRatings, "content", contentRatings));
        });

        app.get("/api/artists", ctx -> ctx.json(DatabaseHandler.getAllArtists()));

        // --- AUTH & POST ROUTES ---
        app.post("/api/login", ctx -> {
            if (ctx.body().equals(config.key)) ctx.status(200).result("Authenticated");
            else ctx.status(401).result("Invalid Code");
        });

        app.post("/api/auth/verify", ctx -> {
            if (ctx.body().equals(config.key)) ctx.status(200).result("OK");
            else ctx.status(401).result("Invalid Code");
        });

        app.post("/api/media/caption", ctx -> {
            String authHeader = ctx.header("Authorization");
            if (authHeader == null || !authHeader.equals(config.key)) {
                ctx.status(401).result("Unauthorized");
                return;
            }
            String mediaId = ctx.queryParam("mediaId");
            if (mediaId != null) {
                DatabaseHandler.setMediaCaption(mediaId, ctx.queryParam("caption"));
                ctx.status(200).result("OK");
            } else {
                ctx.status(400).result("Missing mediaId");
            }
        });

        app.before("/api/rate/*", ctx -> {
            String authHeader = ctx.header("Authorization");
            if (authHeader == null || !authHeader.equals(config.key)) {
                ctx.status(401).result("Unauthorized: Admin code required to change ratings.");
            }
        });

        app.post("/api/rate/media", ctx -> {
            int mediaId = Integer.parseInt(ctx.queryParam("mediaId"));
            String type = ctx.queryParam("type");
            String value = ctx.queryParam("value");
            if (type.equals("Safety")) DatabaseHandler.setMediaRatings(mediaId, null, value);
            else DatabaseHandler.setMediaRatings(mediaId, value, null);
            ctx.status(200).result("OK");
        });

        app.post("/api/rate/post", ctx -> {
            String postId = ctx.queryParam("postId");
            String type = ctx.queryParam("type");
            String value = ctx.queryParam("value");
            if ("Safety".equalsIgnoreCase(type)) DatabaseHandler.setPostRatings(postId, null, value);
            else DatabaseHandler.setPostRatings(postId, value, null);
            ctx.status(200).result("OK");
        });

        // --- SPA ROUTING ---
        Handler spaHandler = ctx -> {
            Path path = Paths.get("public/index.html");
            if (Files.exists(path)) ctx.html(Files.readString(path));
            else ctx.status(404).result("index.html not found in public/ folder");
        };

        app.get("/artists", spaHandler);
        app.get("/artist/{name}", spaHandler);
        app.get("/account/{id}", spaHandler);
        app.get("/post/{id}", spaHandler);
        app.get("/media/{id}", spaHandler);

        // --- 404 & DIRECTORY LISTING FALLBACK ---
        app.error(404, ctx -> {
            if (ctx.path().startsWith("/images")) {
                serveDirectoryListing(ctx);
            } else if (!ctx.path().startsWith("/api")) {
                ctx.html(Files.readString(Paths.get("public/index.html")));
            }
        });

        System.out.println("Web Interface started at http://localhost:" + config.port);
    }

    /**
     * Handles directory listing for /images/ endpoints when a specific file isn't requested.
     */
    private static void serveDirectoryListing(Context ctx) throws IOException {
        String reqPath = ctx.path().replaceFirst("^/images/?", "");
        Path targetPath = Paths.get(config.imageDownloadPath, reqPath).normalize();

        // Security Check: Prevent directory traversal
        if (!targetPath.startsWith(Paths.get(config.imageDownloadPath).normalize())) {
            ctx.status(403).result("Forbidden");
            return;
        }

        if (!Files.isDirectory(targetPath)) {
            ctx.status(404).result("File not found");
            return;
        }

        // --- NEW: Fix the missing trailing slash issue ---
        // If the path is a valid directory but the URL doesn't end with "/", redirect them.
        if (!ctx.path().endsWith("/")) {
            ctx.redirect(ctx.path() + "/");
            return;
        }
        // --------------------------------------------------

        ctx.status(200);
        StringBuilder listItems = new StringBuilder();

        // Add parent directory link if not at root
        if (!reqPath.isEmpty()) {
            listItems.append("<li><a href=\"../\">../ (Parent Directory)</a></li>\n");
        }

        // Generate links for directories and files
        try (Stream<Path> paths = Files.list(targetPath)) {
            paths.sorted((p1, p2) -> {
                boolean d1 = Files.isDirectory(p1);
                boolean d2 = Files.isDirectory(p2);
                if (d1 && !d2) return -1;
                if (!d1 && d2) return 1;
                return p1.getFileName().compareTo(p2.getFileName());
            }).forEach(p -> {
                String name = p.getFileName().toString();
                String href = Files.isDirectory(p) ? name + "/" : name;
                listItems.append("<li><a href=\"").append(href).append("\">").append(href).append("</a></li>\n");
            });
        } catch (Exception e) {
            ctx.status(500).result("Error reading directory contents.");
            return;
        }

        // Load the external HTML file and replace the placeholders
        Path templatePath = Paths.get("public/directory.html");
        if (Files.exists(templatePath)) {
            String html = Files.readString(templatePath)
                    .replace("{{PATH}}", "/images/" + reqPath)
                    .replace("{{FILES}}", listItems.toString());
            ctx.html(html);
        } else {
            // Failsafe in case directory.html goes missing
            ctx.html("<ul>" + listItems + "</ul>");
        }
    }
}