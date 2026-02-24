package katworks.web;

import io.javalin.Javalin;
import io.javalin.http.Handler;
import io.javalin.http.staticfiles.Location;
import katworks.database.DatabaseHandler;
import katworks.impl.ArtistDetails;
import katworks.impl.TwitterAccount;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static katworks.Main.config;

public class WebServer {

    public static void start() {
        Javalin app = Javalin.create(figgy -> {
            // 1. Serve static HTML/JS from your resources folder
            figgy.staticFiles.add("public/", Location.EXTERNAL);

            // 2. Serve your ARCHIVE images (important!)
            // This maps http://localhost:7070/images/ to your hard drive folder
            figgy.staticFiles.add(staticFiles -> {
                staticFiles.directory = config.imageDownloadPath;
                staticFiles.location = Location.EXTERNAL;
                staticFiles.hostedPath = "/images";
            });
        }).start(config.port);

        // API Routes (Keep your existing ones, add these)
        app.get("/api/posts/global", ctx -> {
            int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(20);
            int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);

            List<String> contentFilters = ctx.queryParams("c");
            List<String> safetyFilters = ctx.queryParams("s");
            String sort = ctx.queryParam("sort");

            ctx.json(DatabaseHandler.getGlobalPostsPaged(limit, offset, contentFilters, safetyFilters, sort));
        });

        // --- API ROUTES ---

        app.get("/api/artists/search", ctx -> {
            ctx.json(DatabaseHandler.searchArtists(ctx.queryParam("q")));
        });

        app.get("/api/accounts/search", ctx -> {
            ctx.json(DatabaseHandler.searchAccounts(ctx.queryParam("q")));
        });

        // Get all active accounts
        app.get("/api/accounts/active", ctx -> {
            ctx.json(DatabaseHandler.getActiveAccounts());
        });

        //get all accounts
        app.get("/api/accounts", ctx -> {
            ctx.json(DatabaseHandler.getAllAccounts());
        });

        // Add to WebServer.java routes
        app.get("/api/artists/name/{name}", ctx -> {
            ArtistDetails details = DatabaseHandler.getArtistDetailsByName(ctx.pathParam("name"));
            if (details == null) ctx.status(404);
            else ctx.json(details);
        });

        app.get("/api/artists/{id}", ctx -> {
            ctx.json(DatabaseHandler.getArtistDetails(Integer.parseInt(ctx.pathParam("id"))));
        });

        app.get("/api/accounts/{id}", ctx -> {
            ctx.json(DatabaseHandler.getAccountById(ctx.pathParam("id")));
        });

        /* commented out because this just returns every single post
        // Get posts for a specific user
        app.get("/api/posts/{twitterId}", ctx -> {
            String twitterId = ctx.pathParam("twitterId");
            ctx.json(DatabaseHandler.getPostsByUserId(twitterId));
        });
        */

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

        //endpoint for listing valid safety and content ratings
        app.get("/api/config", ctx -> {
            List<String> safetyRatings = new ArrayList<>(config.safetyRatings);
            List<String> contentRatings = new ArrayList<>(config.contentRatings);
            safetyRatings.remove("Waiting");
            contentRatings.remove("Waiting");
            ctx.json(Map.of("safety", safetyRatings, "content", contentRatings));
        });

        //media update endpoint
        app.post("/api/rate/media", ctx -> {
            int mediaId = Integer.parseInt(ctx.queryParam("mediaId"));
            String type = ctx.queryParam("type"); // "Safety" or "Content"
            String value = ctx.queryParam("value");

            if (type.equals("Safety")) DatabaseHandler.setMediaRatings(mediaId, null, value);
            else DatabaseHandler.setMediaRatings(mediaId, value, null);

            ctx.status(200).result("OK");
        });

        app.post("/api/rate/post", ctx -> {
            String postId = ctx.queryParam("postId");
            String type = ctx.queryParam("type"); // "Safety" or "Content"
            String value = ctx.queryParam("value");

            if ("Safety".equalsIgnoreCase(type)) DatabaseHandler.setPostRatings(postId, null, value);
            else DatabaseHandler.setPostRatings(postId, value, null);

            ctx.status(200).result("OK");
        });

        // 1. Add a Login verification endpoint
        app.post("/api/login", ctx -> {
            String code = ctx.body(); // Plain text code
            if (code.equals(config.key)) {
                ctx.status(200).result("Authenticated");
            } else {
                ctx.status(401).result("Invalid Code");
            }
        });

        app.post("/api/auth/verify", ctx -> {
            if (ctx.body().equals(config.key)) {
                ctx.status(200).result("OK");
            } else {
                ctx.status(401).result("Invalid Code");
            }
        });

        app.post("/api/media/caption", ctx -> {
            // Check authentication exactly like your rating routes
            String authHeader = ctx.header("Authorization");
            if (authHeader == null || !authHeader.equals(config.key)) {
                ctx.status(401).result("Unauthorized");
                return;
            }

            String mediaId = ctx.queryParam("mediaId");
            String caption = ctx.queryParam("caption");

            if (mediaId != null) {
                DatabaseHandler.setMediaCaption(mediaId, caption);
                ctx.status(200).result("OK");
            } else {
                ctx.status(400).result("Missing mediaId");
            }
        });

        // 2. Add an Access Filter for all POST routes (the update routes)
        app.before("/api/rate/*", ctx -> {
            String authHeader = ctx.header("Authorization");
            // Check if header matches the secret
            if (authHeader == null || !authHeader.equals(config.key)) {
                ctx.status(401).result("Unauthorized: Admin code required to change ratings.");
            }
        });

        Handler spaHandler = ctx -> {
            // Points to your external public folder
            Path path = Paths.get("public/index.html");
            if (Files.exists(path)) {
                ctx.html(Files.readString(path));
            } else {
                ctx.status(404).result("index.html not found in public/ folder");
            }
        };

        // SPA ROUTING: If it's not an API call and not a file, serve index.html
        app.get("/artist/{name}", spaHandler);
        app.get("/account/{id}", spaHandler);
        app.get("/post/{id}", spaHandler);
        app.get("/media/{id}", spaHandler);

        app.error(404, ctx -> {
            // If the user refreshed on a route like /artist/someone,
            // it technically 404s on the static file check, so we send index.html
            if (!ctx.path().startsWith("/api") && !ctx.path().startsWith("/images")) {
                ctx.html(Files.readString(Paths.get("public/index.html")));
            }
        });

        System.out.println("Web Interface started at http://localhost:7070");
    }
}