package katworks.web;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.staticfiles.Location;
import katworks.database.DatabaseHandler;
import katworks.impl.*;
import katworks.twitter.TwitterScraper;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static katworks.Main.config;

public class WebServer {

    // Maps a secure random string (Cookie) to a User's ID and Role.
    public record UserSession(int userId, String username, String role) {}
    private static final Map<String, UserSession> activeSessions = new ConcurrentHashMap<>();
    private static final java.security.SecureRandom secureRandom = new java.security.SecureRandom();

    private static String generateSessionToken() {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private static final Map<String, byte[]> thumbnailCache = Collections.synchronizedMap(
            new LinkedHashMap<String, byte[]>(500, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > 500;
                }
            }
    );

    public static void start() {
        Javalin.create(figgy -> {
            figgy.staticFiles.add("public/", Location.EXTERNAL);
            figgy.staticFiles.add(staticFiles -> {
                staticFiles.directory = config.imageDownloadPath;
                staticFiles.location = Location.EXTERNAL;
                staticFiles.hostedPath = "/images";
            });

            // ==========================================
            // 1. GLOBAL AUTHENTICATION & RBAC FILTER
            // ==========================================
            figgy.routes.before("/api/*", ctx -> {
                String method = ctx.method().name();
                String path = ctx.path();

                // 1. Allow public access to GET requests and Auth routes
                if (method.equals("GET") || path.startsWith("/api/auth") || path.equals("/api/login") || path.equals("/api/logout")) {
                    return;
                }

                // 2. Verify Session Cookie
                String sessionId = ctx.cookie("SESSION_ID");
                if (sessionId == null || !activeSessions.containsKey(sessionId)) {
                    throw new io.javalin.http.UnauthorizedResponse("Unauthorized: Please log in.");
                }

                UserSession session = activeSessions.get(sessionId);

                // 3. Define Role Hierarchy (Higher number = more permissions)
                int userLevel = switch (session.role()) {
                    case "Execute" -> 3;
                    case "Write"  -> 2;
                    default      -> 1; // "Read" or unknown
                };

                // 4. Protect Endpoints based on Role
                // Execute (Level 3) is required for managing accounts, tasks, and users
                if (path.startsWith("/api/accounts") || path.startsWith("/api/tasks") || path.startsWith("/api/artists") ||
                        path.startsWith("/api/keys") || path.startsWith("/api/users")) {
                    if (userLevel < 3) {
                        throw new io.javalin.http.ForbiddenResponse("Forbidden: Execute access required.");
                    }
                }
                else if (path.startsWith("/api/media") || path.startsWith("/api/posts")) {
                    if (userLevel < 2) {
                        throw new io.javalin.http.ForbiddenResponse("Forbidden: Editor access required.");
                    }
                }
            });

            // ==========================================
            // 2. PUBLIC DATA ROUTES (Unauthenticated)
            // ==========================================
            figgy.routes.get("/api/config", ctx -> {
                List<String> safetyRatings = new ArrayList<>(config.safetyRatings);
                List<String> contentRatings = new ArrayList<>(config.contentRatings);
                safetyRatings.remove("Waiting");
                contentRatings.remove("Waiting");
                ctx.json(Map.of("safety", safetyRatings, "content", contentRatings));
            });

            figgy.routes.get("/api/posts", ctx -> {
                int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(20);
                int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);
                List<String> contentFilters = ctx.queryParams("content"); // Changed from 'c'
                List<String> safetyFilters = ctx.queryParams("safety");   // Changed from 's'
                String sort = ctx.queryParam("sort");
                ctx.json(DatabaseHandler.getGlobalPostsPaged(limit, offset, contentFilters, safetyFilters, sort));
            });

            figgy.routes.get("/api/posts/{id}", ctx -> ctx.json(DatabaseHandler.getPostDetails(ctx.pathParam("id"))));

            figgy.routes.get("/api/accounts", ctx -> {
                String q = ctx.queryParam("q");
                String status = ctx.queryParam("status");

                if (q != null && !q.isEmpty()) ctx.json(DatabaseHandler.searchAccounts(q));
                else if ("Active".equalsIgnoreCase(status)) ctx.json(DatabaseHandler.getActiveAccounts());
                else ctx.json(DatabaseHandler.getAllAccounts());
            });

            figgy.routes.get("/api/accounts/{id}", ctx -> ctx.json(DatabaseHandler.getAccountById(ctx.pathParam("id"))));

            // Replaces /api/posts/{twitterId}
            figgy.routes.get("/api/accounts/{id}/posts", ctx -> {
                String twitterId = ctx.pathParam("id");
                int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(20);
                int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);
                List<String> contentFilters = ctx.queryParams("content");
                List<String> safetyFilters = ctx.queryParams("safety");
                String sort = ctx.queryParam("sort");
                ctx.json(DatabaseHandler.getPostsByUserIdPaged(twitterId, limit, offset, contentFilters, safetyFilters, sort));
            });

            // Merged /artists and /artists/search
            figgy.routes.get("/api/artists", ctx -> {
                String q = ctx.queryParam("q");
                if (q != null && !q.isEmpty()) ctx.json(DatabaseHandler.searchArtists(q));
                else ctx.json(DatabaseHandler.getAllArtists());
            });

            figgy.routes.get("/api/artists/{id}", ctx -> ctx.json(DatabaseHandler.getArtistDetailsById(Integer.parseInt(ctx.pathParam("id")))));

            figgy.routes.get("/api/artists/slug/{name}", ctx -> {
                ArtistDetails details = DatabaseHandler.getArtistDetailsByName(ctx.pathParam("name"));
                if (details == null) ctx.status(404);
                else ctx.json(details);
            });

            figgy.routes.get("/api/media/{id}", ctx -> ctx.json(DatabaseHandler.getMediaById(Integer.parseInt(ctx.pathParam("id")))));

            figgy.routes.get("/api/media/{id}/thumbnail", ctx -> {
                int mediaId = Integer.parseInt(ctx.pathParam("id"));
                TwitterMedia media = DatabaseHandler.getMediaById(mediaId);

                if (media == null) {
                    ctx.status(404).result("Media not found");
                    return;
                }

                String filename = media.localPath;
                String cacheKey = mediaId + "-thumb";

                if (thumbnailCache.containsKey(cacheKey)) {
                    ctx.contentType("image/jpeg").result(thumbnailCache.get(cacheKey));
                    return;
                }

                Path targetPath = Paths.get(filename).normalize();

                if (!targetPath.startsWith(Paths.get(config.imageDownloadPath).normalize()) || !Files.exists(targetPath)) {
                    ctx.status(404).result("File not found");
                    return;
                }

                if (filename.toLowerCase().endsWith(".mp4") || filename.toLowerCase().endsWith(".webm")) {
                    ctx.redirect("/images/" + filename);
                    return;
                }

                try {
                    long fileSize = Files.size(targetPath);
                    if (fileSize > 10 * 1024 * 1024) { // 10 MB limit
                        ctx.status(413).result("Image too large to generate thumbnail");
                        return;
                    }
                    BufferedImage original = ImageIO.read(targetPath.toFile());
                    if (original == null) {
                        ctx.status(415).result("Unsupported media type");
                        return;
                    }

                    int targetWidth = 400;
                    int origW = original.getWidth();
                    int origH = original.getHeight();
                    byte[] responseData;

                    if (origW > targetWidth) {
                        int targetHeight = (origH * targetWidth) / origW;
                        BufferedImage thumb = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
                        Graphics2D g2d = thumb.createGraphics();
                        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        g2d.drawImage(original, 0, 0, targetWidth, targetHeight, null);
                        g2d.dispose();

                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        ImageIO.write(thumb, "jpg", baos);
                        responseData = baos.toByteArray();
                    } else {
                        responseData = Files.readAllBytes(targetPath);
                    }

                    thumbnailCache.put(cacheKey, responseData);
                    ctx.contentType("image/jpeg").result(responseData);

                } catch (Exception e) {
                    e.printStackTrace();
                    ctx.status(500).result("Error generating thumbnail");
                }
            });

            // ==========================================
            // 3. AUTHENTICATED ROUTES
            // ==========================================
            figgy.routes.post("/api/auth/register", ctx -> {
                @SuppressWarnings("unchecked")
                Map<String, String> req = ctx.bodyAsClass(Map.class);
                String username = req.get("username");
                String email = req.get("email");
                String password = req.get("password");
                String inviteKey = req.get("inviteKey"); // Optional

                if (username == null || password == null || password.length() < 6) {
                    ctx.status(400).json(Map.of("success", false, "error", "Invalid username or password (min 6 chars)"));
                    return;
                }

                String result = DatabaseHandler.registerUser(username, email, password, inviteKey);
                if (result.equals("Success")) {
                    ctx.json(Map.of("success", true, "message", "Registration successful. You can now log in."));
                } else {
                    ctx.status(400).json(Map.of("success", false, "error", result));
                }
            });

            figgy.routes.post("/api/login", ctx -> {
                @SuppressWarnings("unchecked")
                Map<String, String> req = ctx.bodyAsClass(Map.class);
                String identifier = req.get("identifier"); // Can be username or email
                String password = req.get("password");

                ArchiveUser user = DatabaseHandler.getUserByIdentifier(identifier);

                // Check user exists, is not banned, and password matches
                if (user != null && !user.banned && org.mindrot.jbcrypt.BCrypt.checkpw(password, user.passwordHash)) {

                    // Generate secure token and save to memory
                    String token = generateSessionToken();
                    activeSessions.put(token, new UserSession(user.id, user.username, user.role));

                    // Send the token to the browser as an HttpOnly cookie
                    // Hardcoding the header is the most version-proof way across all Javalin versions
                    ctx.header("Set-Cookie", "SESSION_ID=" + token + "; HttpOnly; Path=/; SameSite=Strict");

                    ctx.json(Map.of("success", true, "role", user.role, "username", user.username));
                } else {
                    ctx.status(401).json(Map.of("success", false, "error", "Invalid credentials or banned account."));
                }
            });

            figgy.routes.post("/api/logout", ctx -> {
                String sessionId = ctx.cookie("SESSION_ID");
                if (sessionId != null) activeSessions.remove(sessionId);

                // Clear the cookie in the browser
                ctx.header("Set-Cookie", "SESSION_ID=; HttpOnly; Path=/; Max-Age=0; SameSite=Strict");
                ctx.json(Map.of("success", true));
            });

            figgy.routes.get("/api/auth/me", ctx -> {
                String sessionId = ctx.cookie("SESSION_ID");
                if (sessionId != null && activeSessions.containsKey(sessionId)) {
                    UserSession session = activeSessions.get(sessionId);
                    ctx.json(Map.of("success", true, "username", session.username(), "role", session.role()));
                } else {
                    ctx.status(401).json(Map.of("success", false));
                }
            });

            figgy.routes.patch("/api/media/{id}", ctx -> {
                int mediaId = Integer.parseInt(ctx.pathParam("id"));
                @SuppressWarnings("unchecked")
                Map<String, Object> req = ctx.bodyAsClass(Map.class);

                if (req.containsKey("caption")) {
                    DatabaseHandler.setMediaCaption(String.valueOf(mediaId), (String) req.get("caption"));
                }
                if (req.containsKey("contentRating") || req.containsKey("safetyRating")) {
                    String content = (String) req.get("contentRating");
                    String safety = (String) req.get("safetyRating");
                    DatabaseHandler.setMediaRatings(mediaId, content, safety);
                }

                ctx.json(Map.of("success", true, "message", "Media updated"));
            });

            figgy.routes.patch("/api/posts/{id}", ctx -> {
                String postId = ctx.pathParam("id");
                @SuppressWarnings("unchecked")
                Map<String, String> req = ctx.bodyAsClass(Map.class);

                String content = req.get("contentRating");
                String safety = req.get("safetyRating");
                if (content != null || safety != null) {
                    DatabaseHandler.setPostRatings(postId, content, safety);
                }

                ctx.json(Map.of("success", true, "message", "Post updated"));
            });

            figgy.routes.get("/api/me", ctx -> {
                UserSession session = activeSessions.get(ctx.cookie("SESSION_ID"));
                ctx.json(DatabaseHandler.getUserProfile(session.userId()));
            });

            figgy.routes.patch("/api/me", ctx -> {
                UserSession session = activeSessions.get(ctx.cookie("SESSION_ID"));
                @SuppressWarnings("unchecked")
                Map<String, String> req = ctx.bodyAsClass(Map.class);

                String res = DatabaseHandler.updateUserProfile(
                        session.userId(), req.get("username"), req.get("email"), req.get("password"), req.get("aboutMe")
                );

                if (res.equals("Success")) {
                    // Update session token memory in case their username changed
                    activeSessions.put(ctx.cookie("SESSION_ID"), new UserSession(session.userId(), req.get("username"), session.role()));
                    ctx.json(Map.of("success", true, "username", req.get("username")));
                } else {
                    ctx.status(400).json(Map.of("success", false, "error", res));
                }
            });

            figgy.routes.delete("/api/me", ctx -> {
                UserSession session = activeSessions.get(ctx.cookie("SESSION_ID"));
                DatabaseHandler.deleteUser(session.userId());

                // Log them out automatically
                activeSessions.remove(ctx.cookie("SESSION_ID"));
                ctx.header("Set-Cookie", "SESSION_ID=; HttpOnly; Path=/; Max-Age=0; SameSite=Strict");
                ctx.json(Map.of("success", true));
            });

            // ==========================================
            // 4. ADMIN TASK ROUTES
            // ==========================================
            figgy.routes.post("/api/accounts", ctx -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> req = ctx.bodyAsClass(Map.class);
                String handle = (String) req.get("handle");
                String artistName = (String) req.get("artist");
                boolean downloadStatus = (Boolean) req.get("download");
                String accountSafetyRating = (String) req.get("safety");

                CompletableFuture.runAsync(() -> {
                    try {
                        TwitterAccount account = TwitterScraper.getUserProfileByName(handle);
                        if (account != null) {
                            DatabaseHandler.registerAccount(account.twitterId, account.screenName, account.displayName, artistName, downloadStatus, accountSafetyRating);
                        }
                    } catch (Exception e) { e.printStackTrace(); }
                });
                ctx.json(Map.of("success", true, "message", "Task queued: Add Account @" + handle));
            });

            figgy.routes.patch("/api/accounts/{handle}", ctx -> {
                String screenName = ctx.pathParam("handle");
                @SuppressWarnings("unchecked")
                Map<String, Object> req = ctx.bodyAsClass(Map.class);

                if (req.containsKey("displayName")) DatabaseHandler.setDisplayName(screenName, (String) req.get("displayName"));
                if (req.containsKey("accountStatus")) DatabaseHandler.setAccountStatus(screenName, (String) req.get("accountStatus"));
                if (req.containsKey("isProtected")) DatabaseHandler.setProtected(screenName, (Boolean) req.get("isProtected"));
                if (req.containsKey("downloadStatus")) DatabaseHandler.setDownloadStatus(screenName, (Boolean) req.get("downloadStatus"));
                if (req.containsKey("safetyRating")) DatabaseHandler.setAccountSafetyRating(screenName, (String) req.get("safetyRating"));

                ctx.json(Map.of("success", true, "message", "Edits submitted for " + screenName));
            });

            figgy.routes.delete("/api/accounts/{handle}", ctx -> {
                DatabaseHandler.deleteAccountByScreenName(ctx.pathParam("handle"));
                ctx.json(Map.of("success", true, "message", "Account deleted."));
            });

            figgy.routes.patch("/api/artists/{name}", ctx -> {
                String artistName = ctx.pathParam("name");
                @SuppressWarnings("unchecked")
                Map<String, String> req = ctx.bodyAsClass(Map.class);

                if (req.containsKey("description")) {
                    DatabaseHandler.setArtistDescriptionByName(artistName, req.get("description"));
                }
                ctx.json(Map.of("success", true, "message", "Artist updated."));
            });

            figgy.routes.post("/api/artists/{name}/aliases", ctx -> {
                String artistName = ctx.pathParam("name");
                @SuppressWarnings("unchecked")
                Map<String, String> req = ctx.bodyAsClass(Map.class);
                String res = DatabaseHandler.addAlias(artistName, req.get("aliasName"), req.get("safetyRating"));
                ctx.json(Map.of("success", true, "message", res));
            });

            figgy.routes.post("/api/tasks/scrape", ctx -> {
                @SuppressWarnings("unchecked")
                Map<String, String> req = ctx.bodyAsClass(Map.class);
                String postId = req.get("postId");
                CompletableFuture.runAsync(() -> {
                    try {
                        TwitterPost post = TwitterScraper.scrapePostById(postId);
                        TwitterAccount dbAccount = DatabaseHandler.getAccountById(post.twitterId);
                        if (dbAccount == null || dbAccount.twitterId == null) {
                            TwitterAccount profile = TwitterScraper.getUserProfileByName(post.screenName);
                            DatabaseHandler.registerAccount(profile.twitterId, profile.screenName, profile.displayName, profile.screenName, false, "Safe");
                            dbAccount = DatabaseHandler.getAccountById(post.twitterId);
                        }
                        TwitterScraper.scrapeFromPostId(dbAccount, postId,null);
                    } catch (Exception e) { e.printStackTrace(); }
                });
                ctx.json(Map.of("success", true, "message", "Scrape queued from post " + postId));
            });

            figgy.routes.post("/api/tasks/download", ctx -> {
                @SuppressWarnings("unchecked")
                Map<String, String> req = ctx.bodyAsClass(Map.class);
                String url = req.get("url");
                String contentRating = req.get("contentRating");
                String safetyRating = req.get("safetyRating");

                if (url == null || !url.contains("status/")) {
                    ctx.status(400).json(Map.of("success", false, "error", "Invalid URL"));
                    return;
                }

                CompletableFuture.runAsync(() -> {
                    try {
                        String postId = url.split("status/")[1].split("\\?")[0];
                        TwitterPost post = TwitterScraper.scrapePostById(postId);
                        TwitterAccount dbAccount = DatabaseHandler.getAccountById(post.twitterId);
                        if (dbAccount == null || dbAccount.twitterId == null) {
                            TwitterAccount profile = TwitterScraper.getUserProfileByName(post.screenName);
                            DatabaseHandler.registerAccount(profile.twitterId, profile.screenName, profile.displayName, profile.screenName, false, "Safe");
                        }
                        DatabaseHandler.setPostRatings(post.postId, contentRating, safetyRating);
                    } catch (Exception e) { e.printStackTrace(); }
                });
                ctx.json(Map.of("success", true, "message", "Download post queued."));
            });

            figgy.routes.get("/api/keys", ctx -> {
                int page = ctx.queryParamAsClass("page", Integer.class).getOrDefault(1);
                int limit = 10;
                int offset = (page - 1) * limit;
                ctx.json(DatabaseHandler.getKeysPaged(limit, offset));
            });

            figgy.routes.post("/api/keys", ctx -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> req = ctx.bodyAsClass(Map.class);
                String role = (String) req.get("role");
                int maxUses = Integer.parseInt(req.getOrDefault("maxUses", "-1").toString());
                long expiresAt = Long.parseLong(req.getOrDefault("expiresAt", "-1").toString());

                String sessionId = ctx.cookie("SESSION_ID");
                UserSession session = activeSessions.get(sessionId);

                InviteKey newKey = DatabaseHandler.generateKey(role, maxUses, expiresAt, session.userId());
                ctx.json(Map.of("success", true, "key", newKey.inviteKey));
            });

            figgy.routes.patch("/api/keys/{id}", ctx -> {
                int id = Integer.parseInt(ctx.pathParam("id"));
                @SuppressWarnings("unchecked")
                Map<String, Object> req = ctx.bodyAsClass(Map.class);
                String role = (String) req.get("role");
                int maxUses = Integer.parseInt(req.get("maxUses").toString());
                long expiresAt = Long.parseLong(req.getOrDefault("expiresAt", "-1").toString());

                DatabaseHandler.updateKey(id, role, maxUses, expiresAt);
                ctx.json(Map.of("success", true));
            });

            figgy.routes.delete("/api/keys/{id}", ctx -> {
                int id = Integer.parseInt(ctx.pathParam("id"));
                DatabaseHandler.deleteKey(id);
                ctx.json(Map.of("success", true));
            });

            figgy.routes.get("/api/users", ctx -> {
                int page = ctx.queryParamAsClass("page", Integer.class).getOrDefault(1);
                int limit = 10;
                int offset = (page - 1) * limit;
                ctx.json(DatabaseHandler.getUsersPaged(limit, offset));
            });

            figgy.routes.patch("/api/users/{id}", ctx -> {
                int id = Integer.parseInt(ctx.pathParam("id"));
                @SuppressWarnings("unchecked")
                Map<String, Object> req = ctx.bodyAsClass(Map.class);

                String role = (String) req.get("role");
                boolean banned = (Boolean) req.get("banned");
                String note = (String) req.get("note");

                DatabaseHandler.updateUserAdmin(id, role, banned, note);
                activeSessions.entrySet().removeIf(entry -> entry.getValue().userId() == id);
                ctx.json(Map.of("success", true));
            });

            // ==========================================
            // 5. SPA ROUTING & FALLBACKS
            // ==========================================
            Handler spaHandler = ctx -> {
                Path path = Paths.get("public/index.html");
                if (Files.exists(path)) ctx.html(Files.readString(path));
                else ctx.status(404).result("index.html not found in public/ folder");
            };

            figgy.routes.get("/artists", spaHandler);
            figgy.routes.get("/artist/{name}", spaHandler);
            figgy.routes.get("/account/{id}", spaHandler);
            figgy.routes.get("/post/{id}", spaHandler);
            figgy.routes.get("/media/{id}", spaHandler);
            figgy.routes.get("/admin", spaHandler);
            figgy.routes.get("/admin/keys", spaHandler);
            figgy.routes.get("/admin/users", spaHandler);
            figgy.routes.get("/me", spaHandler);

            figgy.routes.error(404, ctx -> {
                if (ctx.path().startsWith("/images")) serveDirectoryListing(ctx);
                else if (!ctx.path().startsWith("/api")) ctx.html(Files.readString(Paths.get("public/index.html")));
            });
        }).start(config.port);

        System.out.println("Web Interface started at http://localhost:" + config.port);
    }

    private static void serveDirectoryListing(Context ctx) throws IOException {
        String reqPath = ctx.path().replaceFirst("^/images/?", "");
        Path targetPath = Paths.get(config.imageDownloadPath, reqPath).normalize();

        if (!targetPath.startsWith(Paths.get(config.imageDownloadPath).normalize())) {
            ctx.status(403).result("Forbidden");
            return;
        }

        if (!Files.isDirectory(targetPath)) {
            ctx.status(404).result("File not found");
            return;
        }

        if (!ctx.path().endsWith("/")) {
            ctx.redirect(ctx.path() + "/");
            return;
        }

        ctx.status(200);
        StringBuilder listItems = new StringBuilder();
        if (!reqPath.isEmpty()) listItems.append("<li><a href=\"../\">../ (Parent Directory)</a></li>\n");

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

        Path templatePath = Paths.get("public/directory.html");
        if (Files.exists(templatePath)) {
            String html = Files.readString(templatePath).replace("{{PATH}}", "/images/" + reqPath).replace("{{FILES}}", listItems.toString());
            ctx.html(html);
        } else {
            ctx.html("<ul>" + listItems + "</ul>");
        }
    }
}