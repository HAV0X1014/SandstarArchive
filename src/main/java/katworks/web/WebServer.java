package katworks.web;

import io.github.yuvraj0028.models.HashType;
import io.github.yuvraj0028.service.ImageSimilarityService;
import io.javalin.Javalin;
import io.javalin.config.SizeUnit;
import io.javalin.http.*;
import io.javalin.http.staticfiles.Location;
import katworks.database.DatabaseHandler;
import katworks.impl.*;
import katworks.twitter.TwitterScraper;
import katworks.util.ExtractPostId;
import org.mindrot.jbcrypt.BCrypt;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static katworks.Main.config;

public class WebServer {

    private static final SecureRandom secureRandom = new SecureRandom();

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

    // Helper for generating proper external URLs for OpenGraph tags
    private static String getMediaUrl(String host, TwitterMedia m) {
        try {
            String encodedContent = URLEncoder.encode(m.contentRating, StandardCharsets.UTF_8).replace("+", "%20");
            String encodedSafety = URLEncoder.encode(m.safetyRating, StandardCharsets.UTF_8).replace("+", "%20");
            String encodedFile = URLEncoder.encode(Paths.get(m.localPath).getFileName().toString(), StandardCharsets.UTF_8).replace("+", "%20");
            return host + "/images/" + encodedContent + "/" + encodedSafety + "/" + encodedFile;
        } catch (Exception e) {
            return host + "/api/media/" + m.id + "/thumbnail"; // safe fallback
        }
    }

    // Helper to sanitize HTML input for injection into meta tags
    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    public static void start() {
        Javalin.create(figgy -> {
            figgy.staticFiles.add("public/", Location.EXTERNAL);
            figgy.staticFiles.add(staticFiles -> {
                staticFiles.directory = config.imageDownloadPath;
                staticFiles.location = Location.EXTERNAL;
                staticFiles.hostedPath = "/images";
            });
            figgy.jetty.multipartConfig.maxFileSize(10, SizeUnit.MB);

            // ==========================================
            // 1. GLOBAL AUTHENTICATION & RBAC FILTER
            // ==========================================
            figgy.routes.before("/api/*", ctx -> {
                String method = ctx.method().name();
                String path = ctx.path();

                // 1. Identify explicitly public routes
                boolean isAuthRoute = path.startsWith("/api/auth") || path.equals("/api/login") || path.equals("/api/logout") || path.equals("/api/search/image");
                boolean isPublicDataGet = method.equals("GET") && (
                        path.startsWith("/api/posts") ||
                                path.startsWith("/api/accounts") ||
                                path.startsWith("/api/artists") ||
                                path.startsWith("/api/media") ||
                                path.startsWith("/api/v1/statuses/") ||
                                path.startsWith("/users/") ||
                                path.equals("/api/config")
                );

                // 2. Extract Token from Cookie or Bearer Header
                String token = ctx.cookie("SESSION_ID");
                if (token == null) {
                    String authHeader = ctx.header("Authorization");
                    if (authHeader != null && authHeader.startsWith("Bearer ")) {
                        token = authHeader.substring(7).trim();
                    }
                }

                // 3. Attempt to retrieve user from DB if token exists
                ArchiveUser session = null;
                if (token != null) {
                    session = DatabaseHandler.getSessionByToken(token);
                }

                // 4. Attach session to the context so handlers (like /api/me) can use it
                if (session != null) {
                    ctx.attribute("userSession", session);
                }

                // 5. If route is public, stop the interceptor here and allow access
                if (isAuthRoute || isPublicDataGet) {
                    return;
                }

                // 6. PROTECTED ROUTES: Enforce login requirement
                if (session == null) {
                    throw new UnauthorizedResponse("Unauthorized: Missing, invalid, or expired token.");
                }

                // 7. RBAC Authorization for protected routes
                int userLevel = switch (session.role) {
                    case "Execute" -> 3;
                    case "Write"  -> 2;
                    default      -> 1; // "Read" or unknown
                };

                // Admin endpoints
                if (path.startsWith("/api/accounts") || path.startsWith("/api/tasks") || path.startsWith("/api/artists") ||
                        path.startsWith("/api/keys") || path.startsWith("/api/users")) {
                    if (userLevel < 3) {
                        throw new ForbiddenResponse("Forbidden: Execute access required.");
                    }
                }
                // Editor endpoints
                else if (path.startsWith("/api/media") || path.startsWith("/api/posts")) {
                    if (userLevel < 2) {
                        throw new ForbiddenResponse("Forbidden: Editor access required.");
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

            figgy.routes.get("/api/accounts/{id}/posts", ctx -> {
                String twitterId = ctx.pathParam("id");
                int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(20);
                int offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);
                List<String> contentFilters = ctx.queryParams("content");
                List<String> safetyFilters = ctx.queryParams("safety");
                String sort = ctx.queryParam("sort");
                ctx.json(DatabaseHandler.getPostsByUserIdPaged(twitterId, limit, offset, contentFilters, safetyFilters, sort));
            });

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

            figgy.routes.post("/api/search/image", ctx -> {
                UploadedFile file = ctx.uploadedFile("image");
                int threshold = ctx.queryParamAsClass("threshold", Integer.class).getOrDefault(10);

                if (threshold > 15) threshold = 15;
                if (threshold < 0) threshold = 0;

                if (file == null) {
                    ctx.status(400).json(Map.of("error", "No image uploaded"));
                    return;
                }

                try {
                    BufferedImage uploadedImage = ImageIO.read(file.content());
                    if (uploadedImage == null) {
                        ctx.status(400).json(Map.of("error", "Invalid image file"));
                        return;
                    }

                    ImageSimilarityService service = new ImageSimilarityService();
                    long uploadPHash = service.computeHash(uploadedImage, HashType.PHASH);

                    List<TwitterMedia> allMedia = DatabaseHandler.getAllMediaWithHashes();
                    List<Map<String, Object>> results = new ArrayList<>();

                    for (TwitterMedia m : allMedia) {
                        if (m.perceptualHash == null || m.perceptualHash.isEmpty()) continue;

                        try {
                            long dbPHash = Long.parseLong(m.perceptualHash);
                            int distance = DatabaseHandler.getHammingDistance(uploadPHash, dbPHash);

                            if (distance <= threshold) {
                                Map<String, Object> match = new HashMap<>();
                                match.put("media", m);
                                match.put("distance", distance);
                                results.add(match);
                            }
                        } catch (NumberFormatException e) {
                            // Skip malformed hashes
                        }
                    }

                    results.sort(Comparator.comparingInt(a -> (int) a.get("distance")));
                    ctx.json(results);

                } catch (Exception e) {
                    e.printStackTrace();
                    ctx.status(500).json(Map.of("error", "Internal processing error"));
                }
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
                    if (fileSize > 100 * 1024 * 1024) { // 100 MB limit
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
                String inviteKey = req.get("inviteKey");

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
                String identifier = req.get("identifier");
                String password = req.get("password");

                ArchiveUser user = DatabaseHandler.getUserByIdentifier(identifier);

                if (user != null && !user.banned && BCrypt.checkpw(password, user.passwordHash)) {

                    // Expiration: 3 Months (Approx 90 Days)
                    long secondsInThreeMonths = 90L * 24 * 60 * 60;
                    long expiresAt = Instant.now().getEpochSecond() + secondsInThreeMonths;

                    // Fetch existing token if already available, or construct a new persistent one
                    String token = DatabaseHandler.getOrCreateUserToken(user.id, expiresAt);

                    ctx.header("Set-Cookie", "SESSION_ID=" + token + "; HttpOnly; Path=/; SameSite=Strict; Max-Age=" + secondsInThreeMonths);
                    ctx.json(Map.of("success", true, "role", user.role, "username", user.username));
                } else {
                    ctx.status(401).json(Map.of("success", false, "error", "Invalid credentials or banned account."));
                }
            });

            figgy.routes.post("/api/logout", ctx -> {
                //disabled because there is only one token per account and deleting it is pointless - or worse - will break all other sessions
                /*
                String sessionId = ctx.cookie("SESSION_ID");
                if (sessionId != null) {
                    DatabaseHandler.deleteSession(sessionId);
                }
                 */

                ctx.header("Set-Cookie", "SESSION_ID=; HttpOnly; Path=/; Max-Age=0; SameSite=Strict");
                ctx.json(Map.of("success", true));
            });

            figgy.routes.get("/api/auth/me", ctx -> {
                String sessionId = ctx.cookie("SESSION_ID");
                if (sessionId != null) {
                    ArchiveUser session = DatabaseHandler.getSessionByToken(sessionId);
                    if (session != null) {
                        ctx.json(Map.of("success", true, "username", session.username, "role", session.role));
                        return;
                    }
                }
                ctx.status(401).json(Map.of("success", false));
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
                ArchiveUser session = ctx.attribute("userSession");
                if (session == null) {
                    ctx.status(401);
                    return;
                }
                ctx.json(DatabaseHandler.getUserProfile(session.id));
            });

            figgy.routes.patch("/api/me", ctx -> {
                ArchiveUser session = ctx.attribute("userSession");
                if (session == null) {
                    ctx.status(401);
                    return;
                }
                @SuppressWarnings("unchecked")
                Map<String, String> req = ctx.bodyAsClass(Map.class);

                String res = DatabaseHandler.updateUserProfile(
                        session.id, req.get("username"), req.get("email"), req.get("password"), req.get("aboutMe")
                );

                if (res.equals("Success")) {
                    // Force re-login if password or user data was updated to keep details accurate
                    String sessionId = ctx.cookie("SESSION_ID");
                    if (sessionId != null) {
                        DatabaseHandler.deleteSession(sessionId);
                    }
                    ctx.header("Set-Cookie", "SESSION_ID=; HttpOnly; Path=/; Max-Age=0; SameSite=Strict");
                    ctx.json(Map.of("success", true, "username", req.get("username"), "requiresReauth", true));
                } else {
                    ctx.status(400).json(Map.of("success", false, "error", res));
                }
            });

            figgy.routes.delete("/api/me", ctx -> {
                ArchiveUser session = ctx.attribute("userSession");
                if (session == null) {
                    ctx.status(401);
                    return;
                }
                DatabaseHandler.deleteUser(session.id);
                DatabaseHandler.deleteSessionsByUserId(session.id);

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
                        String postId = ExtractPostId.extract(url);
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

                ArchiveUser session = ctx.attribute("userSession");
                if (session == null) {
                    ctx.status(401);
                    return;
                }

                InviteKey newKey = DatabaseHandler.generateKey(role, maxUses, expiresAt, session.id);
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

                // Delete active sessions for this updated user
                DatabaseHandler.deleteSessionsByUserId(id);
                ctx.json(Map.of("success", true));
            });

            // Admin: Get the active bot token for a specific user ID
            figgy.routes.get("/api/users/{id}/token", ctx -> {
                int targetUserId = Integer.parseInt(ctx.pathParam("id"));
                String token = DatabaseHandler.getBotTokenByUserId(targetUserId);

                if (token != null) {
                    ctx.json(Map.of("success", true, "token", token));
                } else {
                    ctx.json(Map.of("success", false, "error", "No active bot token exists for this user."));
                }
            });

            // Admin: Generate a new bot token for a specific user ID
            figgy.routes.post("/api/users/{id}/token", ctx -> {
                int targetUserId = Integer.parseInt(ctx.pathParam("id"));
                String newToken = DatabaseHandler.generateBotToken(targetUserId);

                ctx.json(Map.of("success", true, "token", newToken, "message", "Token generated successfully."));
            });

            // Admin: Revoke an existing bot token for a specific user ID
            figgy.routes.delete("/api/users/{id}/token", ctx -> {
                int targetUserId = Integer.parseInt(ctx.pathParam("id"));
                DatabaseHandler.revokeBotToken(targetUserId);

                ctx.json(Map.of("success", true, "message", "Bot token revoked."));
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
            figgy.routes.get("/admin", spaHandler);
            figgy.routes.get("/admin/keys", spaHandler);
            figgy.routes.get("/admin/users", spaHandler);
            figgy.routes.get("/me", spaHandler);

            // Discord OpenGraph intercept handlers for precise embeds:
            figgy.routes.get("/post/{id}", ctx -> {
                String postId = ctx.pathParam("id");
                TwitterPost post = DatabaseHandler.getPostDetails(postId);
                serveEmbedPage(ctx, post, null);
            });

            figgy.routes.get("/media/{id}", ctx -> {
                int mediaId;
                try {
                    mediaId = Integer.parseInt(ctx.pathParam("id"));
                } catch (NumberFormatException e) {
                    ctx.status(400).result("Invalid media ID");
                    return;
                }
                TwitterMedia m = DatabaseHandler.getMediaById(mediaId);
                serveEmbedPage(ctx, null, m);
            });

            // oEmbed endpoint - Discord will call this automatically when it sees the <link rel="alternate"> tag
            figgy.routes.get("/oembed", ctx -> {
                String urlParam = ctx.queryParam("url");
                if (urlParam == null || urlParam.isEmpty()) {
                    ctx.status(400).result("url parameter is required");
                    return;
                }

                String baseUrl = ctx.scheme() + "://" + ctx.host();
                String authorName = "Unknown Artist";
                String screenName = "unknown";
                String description = "No description available.";
                String authorUrl = urlParam; // fallback

                TwitterPost post = null;
                TwitterMedia media = null;
                TwitterAccount account = null;

                try {
                    URI uri = new URI(urlParam);
                    String path = uri.getPath();

                    if (path.startsWith("/post/")) {
                        String postId = path.substring("/post/".length());
                        post = DatabaseHandler.getPostDetails(postId);
                    } else if (path.startsWith("/media/")) {
                        String mediaIdStr = path.substring("/media/".length());
                        int mediaId = Integer.parseInt(mediaIdStr);
                        media = DatabaseHandler.getMediaById(mediaId);
                        if (media != null) {
                            post = DatabaseHandler.getPostDetails(media.postId);
                        }
                    }
                    if (post != null) {
                        account = DatabaseHandler.getAccountById(post.twitterId);
                        authorUrl = baseUrl + "/post/" + post.postId; // always link to post URL

                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

                if (account != null) {
                    authorName = account.displayName != null ? account.displayName : authorName;
                    screenName = account.screenName != null ? account.screenName : screenName;
                }

                if (media != null) {
                    description = (media.caption != null && !media.caption.isEmpty() ? media.caption : "");
                    if (!description.isEmpty()) description += "\n\n";
                    description += "Rating: " + media.contentRating + " / " + media.safetyRating;
                } else if (post != null) {
                    description = (post.postText != null ? post.postText : "");
                    if (!description.isEmpty()) description += "\n\n";
                    description += "Rating: " + post.contentRating + " / " + post.safetyRating;
                }

                String fullAuthorName = authorName + " (@" + screenName + ")";

                Map<String, Object> oembed = new LinkedHashMap<>();
                oembed.put("type", "rich");
                oembed.put("version", "1.0");
                oembed.put("title","Embed");
                oembed.put("author_name", fullAuthorName);
                oembed.put("author_url", authorUrl);
                oembed.put("provider_name", "Sandstar Archive");
                oembed.put("provider_url", baseUrl);
                //oembed.put("description", description);
                //oembed.put("width", 550);
                //oembed.put("height", 400);

                ctx.contentType("application/json");
                ctx.json(oembed);
            });

            figgy.routes.error(404, ctx -> {
                if (ctx.path().startsWith("/images")) serveDirectoryListing(ctx);
                else if (!ctx.path().startsWith("/api")) ctx.html(Files.readString(Paths.get("public/index.html")));
            });

            //figgy.routes.get("/activity/{id}", WebServer::serveActivityPub);
            figgy.routes.get("/api/v1/statuses/{id}", WebServer::serveActivityPubPost);
            figgy.routes.get("/users/{handle}/statuses/{id}", WebServer::serveActivityPubPost);
        }).start(config.port);

        System.out.println("Web Interface started at http://localhost:" + config.port);
    }

    public static void serveActivityPub(Context ctx) {
        String postId = ctx.pathParam("id");
        TwitterPost post = DatabaseHandler.getPostDetails(postId);

        if (post == null) {
            ctx.status(404).result("Post not found");
            return;
        }

        TwitterAccount account = DatabaseHandler.getAccountById(post.twitterId);
        String hostUrl = ctx.scheme() + "://" + ctx.host();

        // Map the Author
        String screenName = account != null ? account.screenName : "unknown";
        String displayName = (account != null && account.displayName != null && !account.displayName.isEmpty()) ? account.displayName : screenName;

        Map<String, Object> attributedTo = new LinkedHashMap<>();
        attributedTo.put("type", "Person");
        attributedTo.put("id", hostUrl + "/users/" + screenName);
        attributedTo.put("name", displayName);
        attributedTo.put("preferredUsername", screenName);

        // Format the Content (Discord's Mastodon parser uses HTML for text)
        String desc = post.postText != null ? escapeHtml(post.postText) : "";
        if (!desc.isEmpty()) desc += "<br><br>";
        desc += "Rating: " + post.contentRating + " / " + post.safetyRating;
        // ActivityPub line breaks MUST be <br>
        desc = desc.replace("\n", "<br>");

        // Build the main Note
        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("@context", "https://www.w3.org/ns/activitystreams");
        activity.put("type", "Note");
        activity.put("id", hostUrl + "/post/" + postId);
        activity.put("url", hostUrl + "/post/" + postId);
        activity.put("attributedTo", attributedTo);
        activity.put("content", desc);

        if (post.postDate > 0) {
            activity.put("published", Instant.ofEpochSecond(post.postDate).toString());
        }

        // Attach all images/videos
        List<Map<String, Object>> attachments = new ArrayList<>();
        if (post.media != null) {
            for (TwitterMedia m : post.media) {
                if (m.localPath == null) continue;

                String mediaUrl = getMediaUrl(hostUrl, m);
                String mimeType = (m.mediaType != null && m.mediaType.contains("mp4")) ? "video/mp4" : "image/jpeg";

                Map<String, Object> att = new LinkedHashMap<>();
                att.put("type", "Document"); // Mastodon uses "Document" for media
                att.put("mediaType", mimeType);
                att.put("url", mediaUrl);

                if (m.caption != null && !m.caption.isEmpty()) {
                    att.put("name", m.caption); // Alt text
                }
                attachments.add(att);
            }
        }

        if (!attachments.isEmpty()) {
            activity.put("attachment", attachments);
        }

        // Serve as application/activity+json
        ctx.contentType("application/activity+json");
        ctx.json(activity);
    }

    public static void serveActivityPubPost(Context ctx) {
        String postId = ctx.pathParam("id");
        TwitterPost post = DatabaseHandler.getPostDetails(postId);

        if (post == null) {
            ctx.status(404).result("Post not found");
            return;
        }

        TwitterAccount account = DatabaseHandler.getAccountById(post.twitterId);
        String hostUrl = ctx.scheme() + "://" + ctx.host();
        String postUrl = hostUrl + "/post/" + postId;

        String desc = post.postText != null ? escapeHtml(post.postText) : "";
        if (!desc.isEmpty()) desc += "<br><br>";
        desc += "<b>Rating: " + post.contentRating + " / " + post.safetyRating + "</b>";
        desc = desc.replace("\n", "<br>");

        Map<String, Object> activity = new LinkedHashMap<>();
        Map<String, Object> application = new LinkedHashMap<>();
        Map<String, Object> accountMastodon = new LinkedHashMap<>();

        // Rule 1: Context must be an array mimicking Mastodon
        /*activity.put("@context", List.of(
                "https://www.w3.org/ns/activitystreams",
                Map.of("sensitive", "as:sensitive")
        ));
        */
        //activity.put("type", "Note");
        activity.put("id", postId);
        activity.put("url", hostUrl + "/post/" + postId);
        activity.put("uri", hostUrl + "/post/" + postId);
        //if (post.postDate > 0) {
            activity.put("created_at", Instant.ofEpochSecond(post.postDate).toString());
        //}
        activity.put("edited_at",null);
        activity.put("reblog",null);
        activity.put("in_reply_to_id",null);
        activity.put("in_reply_to_account_id",null);
        activity.put("language","en");
        activity.put("content", desc);
        activity.put("spoiler_text","");
        activity.put("visibility","public");

        application.put("name","Sandstar Archive");
        application.put("website",null);
        activity.put("application",application);

        // Rule 2: attributedTo MUST be a URL, not an object.
        //activity.put("attributedTo", hostUrl + "/activity/users/" + screenName);

        // FxTwitter includes these explicitly
        //activity.put("summary", null);
        //activity.put("sensitive", "NSFW".equalsIgnoreCase(post.safetyRating)); // True if NSFW
        accountMastodon.put("id","456");
        accountMastodon.put("display_name",account.displayName);
        accountMastodon.put("username",account.screenName + ")");
        accountMastodon.put("acct",account.screenName);
        accountMastodon.put("url",postUrl);
        accountMastodon.put("uri",postUrl);
        accountMastodon.put("created_at","0");
        accountMastodon.put("locked",false);
        accountMastodon.put("bot",false);
        accountMastodon.put("discoverable",true);
        accountMastodon.put("indexable",false);
        accountMastodon.put("group",false);
        //accountMastodon.put("avatar","https://cdn.discordapp.com/attachments/1100888255483875428/1159591754538942514/genbaneko_transparent.png");
        //accountMastodon.put("avatar_static","https://cdn.discordapp.com/attachments/1100888255483875428/1159591754538942514/genbaneko_transparent.png");
        //accountMastodon.put("header","https://cdn.discordapp.com/attachments/1100888255483875428/1159591754538942514/genbaneko_transparent.png");
        //accountMastodon.put("header_static","https://cdn.discordapp.com/attachments/1100888255483875428/1159591754538942514/genbaneko_transparent.png");
        accountMastodon.put("followers_number",0);
        accountMastodon.put("following_count",0);
        accountMastodon.put("statuses_count",0);
        accountMastodon.put("hide_collections",false);
        accountMastodon.put("noindex",false);
        accountMastodon.put("emojis",List.of());
        accountMastodon.put("roles",List.of());
        accountMastodon.put("fields",List.of());
        activity.put("account",accountMastodon);
        activity.put("media_attachments",List.of());
        activity.put("mentions",List.of());
        activity.put("tags",List.of());
        activity.put("emojis",List.of());
        activity.put("card",null);
        activity.put("poll",null);

        List<Map<String, Object>> attachments = new ArrayList<>();
        if (post.media != null) {
            for (TwitterMedia m : post.media) {
                if (m.localPath == null) continue;

                String mediaUrl = getMediaUrl(hostUrl, m);

                if (m.mediaType.equalsIgnoreCase("png") || m.mediaType.equalsIgnoreCase("jpg")) {
                    Map<String, Object> att = new LinkedHashMap<>();
                    Map<String, Object> meta = new LinkedHashMap<>();
                    Map<String, Object> original = new LinkedHashMap<>();
                    att.put("id", String.valueOf(m.mediaIndex));
                    att.put("type", "image");
                    att.put("url", mediaUrl);
                    att.put("preview_url", null);
                    att.put("preview_remote_url", null);
                    att.put("text_url", null);
                    att.put("description", m.caption);
                    original.put("width", 1200);
                    original.put("height", 800);
                    original.put("size", "1200x800");
                    original.put("aspect", 1.5);
                    meta.put("original", original);
                    att.put("meta", meta);
                    attachments.add(att);
                } else {
                    Map<String, Object> att = new LinkedHashMap<>();
                    Map<String, Object> meta = new LinkedHashMap<>();
                    Map<String, Object> original = new LinkedHashMap<>();
                    att.put("id", String.valueOf(m.mediaIndex));
                    att.put("type", "video");
                    att.put("url", mediaUrl);
                    //att.put("remote_url",mediaUrl);
                    att.put("preview_url","https://cdn.discordapp.com/attachments/1100888255483875428/1159591754538942514/genbaneko_transparent.png");
                    att.put("preview_remote_url",null);
                    att.put("text_url",null);
                    att.put("description", m.caption);
                    original.put("width", 1280);
                    original.put("height", 720);
                    original.put("size", "1280x720");
                    original.put("aspect", 1.778);
                    meta.put("original", original);
                    att.put("meta", meta);
                    attachments.add(att);
                }
            }
        }

        if (!attachments.isEmpty()) {
            activity.put("media_attachments", attachments);
        }

        ctx.contentType("application/activity+json; charset=utf-8");
        ctx.json(activity);
    }

    public static void serveActivityPubUser(Context ctx) {
        String screenName = ctx.pathParam("screenName");
        TwitterAccount account = DatabaseHandler.getAccountByScreenName(screenName); // Assumes you have a method like this

        String hostUrl = ctx.scheme() + "://" + ctx.host();
        String displayName = (account != null && account.displayName != null && !account.displayName.isEmpty()) ? account.displayName : screenName;

        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("@context", "https://www.w3.org/ns/activitystreams");
        actor.put("type", "Person");
        actor.put("id", hostUrl + "/activity/users/" + screenName);
        actor.put("url", hostUrl + "/users/" + screenName); // Fallback URL
        actor.put("name", displayName);
        actor.put("preferredUsername", screenName);

        /*
         * Optional: If you have avatar images, Mastodon puts them here so Discord can show the profile pic!
         * Map<String, Object> icon = new LinkedHashMap<>();
         * icon.put("type", "Image");
         * icon.put("mediaType", "image/jpeg");
         * icon.put("url", hostUrl + "/avatar/url.jpg");
         * actor.put("icon", icon);
         */

        ctx.contentType("application/activity+json; charset=utf-8");
        ctx.json(actor);
    }

    // Shared method to handle OpenGraph and oEmbed injection for Discord
    private static void serveEmbedPage(Context ctx, TwitterPost post, TwitterMedia singleMedia) throws IOException {
        Path path = Paths.get("public/index.html");
        if (!Files.exists(path)) {
            ctx.status(404).result("index.html not found");
            return;
        }

        String html = Files.readString(path);

        if (post == null && singleMedia == null) {
            ctx.html(html);
            return;
        }

        if (post == null) {
            post = DatabaseHandler.getPostDetails(singleMedia.postId);
        }

        TwitterAccount account = post != null ? DatabaseHandler.getAccountById(post.twitterId) : null;
        String hostUrl = ctx.scheme() + "://" + ctx.host();
        StringBuilder og = new StringBuilder();

        String screenName = account != null ? account.screenName : "unknown";

        og.append("<meta property=\"og:site_name\" content=\"Sandstar Archive\" />\n");
        og.append("<meta name=\"og:title\" content=\"" + account.displayName + "(@" + account.screenName + ")\" />\n");
        og.append("<meta name=\"twitter:card\" content=\"summary_large_image\" />\n");
        //og.append("<meta name=\"twitter:site\" content=\"@").append(escapeHtml(screenName)).append("\" />\n");
        //og.append("<meta name=\"twitter:creator\" content=\"@").append(escapeHtml(screenName)).append("\" />\n");

        String desc = "";
        if (singleMedia != null) {
            desc = (singleMedia.caption != null && !singleMedia.caption.isEmpty() ? singleMedia.caption : "");
            if (!desc.isEmpty()) desc += "\n\n";
            desc += "Rating: " + singleMedia.contentRating + " / " + singleMedia.safetyRating;
        } else if (post != null) {
            desc = (post.postText != null ? post.postText : "");
            if (!desc.isEmpty()) desc += "\n\n";
            desc += "Rating: " + post.contentRating + " / " + post.safetyRating;
        }

        og.append("<meta property=\"og:description\" content=\"").append(escapeHtml(desc)).append("\" />\n");
        //og.append("<meta name=\"twitter:description\" content=\"").append(escapeHtml(desc)).append("\" />\n");

        /*if (post != null && post.postDate > 0) {
            String isoDate = Instant.ofEpochSecond(post.postDate).toString();
            og.append("<meta property=\"article:published_time\" content=\"").append(isoDate).append("\" />\n");
        }*/

        //og.append("<meta property=\"og:type\" content=\"article\" />\n");
        og.append("<meta name=\"theme-color\" content=\"#1DA1F2\" />\n");
/*
        List<TwitterMedia> mediaList = singleMedia != null ? List.of(singleMedia) :
                (post != null && post.media != null ? post.media : Collections.emptyList());

        if (!mediaList.isEmpty()) {
            boolean hasVideo = mediaList.stream().anyMatch(m -> m.mediaType != null && m.mediaType.contains("mp4"));

            if (hasVideo) {
                og.append("<meta name=\"twitter:card\" content=\"player\" />\n");
            } else {
                og.append("<meta name=\"twitter:card\" content=\"summary_large_image\" />\n");
            }

            for (TwitterMedia m : mediaList) {
                if (m.localPath == null) continue;
                String mediaUrl = getMediaUrl(hostUrl, m);

                if (m.mediaType != null && m.mediaType.contains("mp4")) {
                    og.append("<meta property=\"og:video\" content=\"").append(mediaUrl).append("\" />\n");
                    og.append("<meta property=\"og:video:secure_url\" content=\"").append(mediaUrl).append("\" />\n");
                    og.append("<meta property=\"og:video:type\" content=\"video/mp4\" />\n");

                    og.append("<meta name=\"twitter:player\" content=\"").append(mediaUrl).append("\" />\n");
                    og.append("<meta name=\"twitter:player:stream\" content=\"").append(mediaUrl).append("\" />\n");
                    og.append("<meta name=\"twitter:player:stream:content_type\" content=\"video/mp4\" />\n");

                    if (m.width > 0 && m.height > 0) {
                        og.append("<meta property=\"og:video:width\" content=\"").append(m.width).append("\" />\n");
                        og.append("<meta property=\"og:video:height\" content=\"").append(m.height).append("\" />\n");
                        og.append("<meta name=\"twitter:player:width\" content=\"").append(m.width).append("\" />\n");
                        og.append("<meta name=\"twitter:player:height\" content=\"").append(m.height).append("\" />\n");
                    }
                } else {
                    og.append("<meta property=\"og:image\" content=\"").append(mediaUrl).append("\" />\n");
                    og.append("<meta name=\"twitter:image\" content=\"").append(mediaUrl).append("\" />\n");

                    if (m.width > 0 && m.height > 0) {
                        og.append("<meta property=\"og:image:width\" content=\"").append(m.width).append("\" />\n");
                        og.append("<meta property=\"og:image:height\" content=\"").append(m.height).append("\" />\n");
                    }
                }
            }
        } else {
            og.append("<meta name=\"twitter:card\" content=\"summary\" />\n");
        }
*/
        // oEmbed discovery link
        String pageUrl = hostUrl + ctx.path();
        String encodedPageUrl = URLEncoder.encode(pageUrl, StandardCharsets.UTF_8);
        String oembedLink = "<link rel=\"alternate\" type=\"application/json+oembed\" href=\"/oembed?url=" + encodedPageUrl + "\" title=\"Sandstar Archive\" />\n";
        og.append(oembedLink);

        //mastodon v1/activitypub spoof links
        String targetId = singleMedia != null ? singleMedia.postId : (post != null ? post.postId : "");
        String activityPubLink = "<link href=\"/users/ThisDoesntMatter/statuses/" + post.postId + "\" rel=\"alternate\" type=\"application/activity+json\"/>\n" +
                "<link href=\"/api/v1/statuses/" + targetId + "\" rel=\"alternate\" type=\"application/activity+json\"/>\n";
        og.append(activityPubLink);

        html = html.replace("</head>", og.toString() + "</head>");
        ctx.html(html);
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
