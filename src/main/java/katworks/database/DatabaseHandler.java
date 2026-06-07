package katworks.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import katworks.impl.*;
import net.dv8tion.jda.api.entities.User;
import org.sqlite.SQLiteConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static katworks.Main.config;
import static katworks.Main.writeQueue;

public class DatabaseHandler {
    private static final String dbUrl = "jdbc:sqlite:" + config.databasePath;
    private static final String ALPHANUMERIC = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HikariDataSource dataSource;

    static {
        HikariConfig hkConfig = new HikariConfig();
        hkConfig.setJdbcUrl(dbUrl);
        // SQLite doesn't need huge pools. 10 is plenty for high concurrency.
        hkConfig.setMaximumPoolSize(10);

        // Pass the SQLite properties directly to the pool so they only happen ONCE
        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.setBusyTimeout(5000);
        sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqliteConfig.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        hkConfig.setDataSourceProperties(sqliteConfig.toProperties());

        dataSource = new HikariDataSource(hkConfig);
    }

    public static Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * Sets the safety rating of a post using the postId as the lookup.
     * @param postId
     * @param newSafetyRating
     * @return
     */
    public static void setSafetyRating(String postId, String newSafetyRating) {
        if (!config.safetyRatings.contains(newSafetyRating) && !newSafetyRating.equals("Waiting")) return;
        setPostRatings(postId, null, newSafetyRating);
    }

    /**
     * Sets the content rating of a post using the postId as the lookup.
     * @param postId
     * @param newContentRating
     * @return
     */
    public static void setContentRating(String postId, String newContentRating) {
        if (!config.contentRatings.contains(newContentRating) && !newContentRating.equals("Waiting")) return;
        setPostRatings(postId, newContentRating, null);
    }

    /**
     * Sets the last scraped twitter ID for the specified twitter account ID.
     * @param twitterId
     * @param lastScrapedId
     * @return
     */
    public static void setLastScrapedId(String twitterId, String lastScrapedId) {
        writeQueue.runAsyncWrite(conn -> {
            String sql = "UPDATE twitter_accounts SET last_scraped_id = ? WHERE twitter_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, lastScrapedId);
                ps.setString(2, twitterId);

                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public static void setLastScrapedIdByName(String screenname, String lastScrapedId) {
        writeQueue.runAsyncWrite(conn -> {
            String sql = "UPDATE twitter_accounts SET last_scraped_id = ? WHERE screen_name = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, lastScrapedId);
                ps.setString(2, screenname);

                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Sets the Discord thread ID for the specified twitter account ID.
     * @param twitterId
     * @param discordThreadId
     * @return
     */
    public static void setDiscordThreadId(String twitterId, String discordThreadId) {
        writeQueue.runAsyncWrite(conn -> {
            String sql = "UPDATE twitter_accounts SET discord_thread_id = ? WHERE twitter_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, discordThreadId);
                ps.setString(2, twitterId);

                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Set the account status of an account by screen name. Status must be "Active", "Deleted", "Suspended"
     */
    public static void setAccountStatus(String screenName, String status) {
        if (status.equals(AccountStatus.ACTIVE) || status.equals(AccountStatus.DELETED) || status.equals(AccountStatus.SUSPENDED)) {
            writeQueue.runAsyncWrite(conn -> {
                String sql = "UPDATE twitter_accounts SET account_status = ? WHERE screen_name = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, status);
                    ps.setString(2, screenName);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });
        } else {
            System.out.println("Invalid account status selected. It must be EXACTLY Active, Deleted, or Suspended.");
            return;
        }
    }

    /**
     * Set the download status of an account by twitter ID. True = Download, False = Do not download.
     * @param screenName
     * @param downloadStaus
     * @return
     */
    public static void setDownloadStatus(String screenName, boolean downloadStaus) {
        writeQueue.runAsyncWrite(conn -> {
            String sql = "UPDATE twitter_accounts SET download_status = ? WHERE screen_name = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setBoolean(1, downloadStaus);
                ps.setString(2, screenName);

                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Set the protected status of an account by twitter ID. True = protected, False = normal.
     * @param screenName
     * @param protect
     * @return
     */
    public static void setProtected(String screenName, boolean protect) {
        writeQueue.runAsyncWrite(conn -> {
            String sql = "UPDATE twitter_accounts SET is_protected = ? WHERE screen_name = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setBoolean(1, protect);
                ps.setString(2, screenName);

                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Set a displayname for an account by screen name.
     * @param screenName
     * @param newDisplayName
     */
    public static void setDisplayName(String screenName, String newDisplayName) {
        writeQueue.runAsyncWrite(conn -> {
            String sql = "UPDATE twitter_accounts SET display_name = ? WHERE screen_name = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1,newDisplayName);
                ps.setString(2,screenName);

                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public static void setAccountSafetyRating(String screenName, String newSafetyRating) {
        if (!config.safetyRatings.contains(newSafetyRating) && !newSafetyRating.equals("Waiting")) return;
        writeQueue.runAsyncWrite(conn -> {
            String sql = "UPDATE twitter_accounts SET safety_rating = ? WHERE screen_name = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1,newSafetyRating);
                ps.setString(2,screenName);

                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public static void deleteAccountByScreenName(String screenName) {
        writeQueue.runAsyncWrite(conn -> {
            String sql = "DELETE FROM twitter_accounts WHERE screen_name = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1,screenName);

                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public static List<TwitterAccount> getAllAccounts() {
        List<TwitterAccount> accounts = new ArrayList<>();
        String sql = "SELECT twitter_id, artist_id, screen_name, display_name, account_status, is_protected, last_scraped_id, download_status, discord_thread_id, safety_rating " +
                "FROM twitter_accounts";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                TwitterAccount account = new TwitterAccount();
                account.twitterId = rs.getString("twitter_id");
                account.artistId = rs.getInt("artist_id");
                account.screenName = rs.getString("screen_name");
                account.displayName = rs.getString("display_name");
                account.accountStatus = rs.getString("account_status");
                account.isProtected = rs.getBoolean("is_protected");
                account.lastScrapedId = rs.getString("last_scraped_id");
                account.downloadStatus = rs.getBoolean("download_status");
                account.discordThreadId = rs.getString("discord_thread_id");
                account.safetyRating = rs.getString("safety_rating");
                accounts.add(account);
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return accounts;
    }

    /**
     * Get a List<TwitterAccount> of all accounts that are allowed to be downloaded.
     * The account must have download_status = true, account_status = 'Active', and is_protected = false.
     * @return
     */
    public static List<TwitterAccount> getActiveAccounts() {
        List<TwitterAccount> accounts = new ArrayList<>();
        String sql = "SELECT twitter_id, artist_id, screen_name, display_name, account_status, is_protected, last_scraped_id, download_status, discord_thread_id, safety_rating " +
                "FROM twitter_accounts " +
                "WHERE download_status = 1 AND account_status = 'Active' AND is_protected = 0";

        // Use the new getConnection() helper here
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                TwitterAccount account = new TwitterAccount();
                account.twitterId = rs.getString("twitter_id");
                account.artistId = rs.getInt("artist_id");
                account.screenName = rs.getString("screen_name");
                account.displayName = rs.getString("display_name");
                account.accountStatus = rs.getString("account_status");
                account.isProtected = rs.getBoolean("is_protected");
                account.lastScrapedId = rs.getString("last_scraped_id");
                account.downloadStatus = rs.getBoolean("download_status");
                account.discordThreadId = rs.getString("discord_thread_id");
                account.safetyRating = rs.getString("safety_rating");
                accounts.add(account);
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return accounts;
    }

    /**
     * Gets all TwitterPosts associated with a given twitterId ordered by date. Includes media.
     * @param twitterId
     * @return
     */
    public static List<TwitterPost> getPostsByUserId(String twitterId) {
        List<TwitterPost> posts = new ArrayList<>();
        //gemini wrote this. it works and is way more safe and efficient than anything i could make.
        //join media and posts tables
        String sql = "SELECT p.post_id, p.twitter_id, p.post_text, p.post_date, p.archive_date, " +
                "p.safety_rating AS p_safety, p.content_rating AS p_content, " +
                "m.id, m.media_type, m.original_url, m.local_path, m.caption, m.media_index, " +
                "m.perceptual_hash, m.data_hash, m.duplicate_of, m.width, m.height, m.filesize, " +
                "m.safety_rating AS m_safety, m.content_rating AS m_content " +
                "FROM posts p " +
                "LEFT JOIN media m ON p.post_id = m.post_id " +
                "WHERE p.twitter_id = ? " +
                "ORDER BY p.post_date DESC, m.media_index ASC";

        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, twitterId);

            try (ResultSet rs = ps.executeQuery()) {
                TwitterPost currentPost = null;

                while (rs.next()) {
                    String rowPostId = rs.getString("post_id");

                    // 1. If this is a new post_id, create the TwitterPost object
                    if (currentPost == null || !currentPost.postId.equals(rowPostId)) {
                        currentPost = new TwitterPost();
                        currentPost.postId = rowPostId;
                        currentPost.twitterId = rs.getString("twitter_id");
                        currentPost.postText = rs.getString("post_text");
                        currentPost.postDate = rs.getLong("post_date");
                        currentPost.archiveDate = rs.getLong("archive_date");
                        currentPost.safetyRating = rs.getString("p_safety");
                        currentPost.contentRating = rs.getString("p_content");
                        currentPost.media = new ArrayList<>(); // Initialize the list

                        posts.add(currentPost);
                    }

                    // 2. Create the TwitterMedia object for the current row
                    int mediaId = rs.getInt("id");
                    if (mediaId != 0) { // If id is 0, it means this post has no media (LEFT JOIN result)
                        TwitterMedia m = new TwitterMedia();
                        m.id = mediaId;
                        m.postId = rowPostId;
                        m.mediaType = rs.getString("media_type");
                        m.originalUrl = rs.getString("original_url");
                        m.localPath = rs.getString("local_path");
                        m.caption = rs.getString("caption");
                        m.mediaIndex = rs.getInt("media_index");
                        m.perceptualHash = rs.getString("perceptual_hash");
                        m.dataHash = rs.getString("data_hash");
                        m.duplicateOf = rs.getInt("duplicate_of");
                        m.width = rs.getInt("width");
                        m.height = rs.getInt("height");
                        m.filesize = rs.getLong("filesize");
                        m.safetyRating = rs.getString("m_safety");
                        m.contentRating = rs.getString("m_content");

                        // 3. Add this media to the list inside the current post
                        currentPost.media.add(m);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return posts;
    }

    public static TwitterAccount getAccountById(String twitterId) {
        TwitterAccount account = new TwitterAccount();
        String sql = "SELECT twitter_id, artist_id, screen_name, display_name, account_status, is_protected, last_scraped_id, download_status, discord_thread_id, safety_rating " +
                "FROM twitter_accounts " +
                "WHERE twitter_id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1,twitterId);
            try (ResultSet rs = ps.executeQuery()) {
                account.twitterId = rs.getString("twitter_id");
                account.artistId = rs.getInt("artist_id");
                account.screenName = rs.getString("screen_name");
                account.displayName = rs.getString("display_name");
                account.accountStatus = rs.getString("account_status");
                account.isProtected = rs.getBoolean("is_protected");
                account.lastScrapedId = rs.getString("last_scraped_id");
                account.downloadStatus = rs.getBoolean("download_status");
                account.discordThreadId = rs.getString("discord_thread_id");
                account.safetyRating = rs.getString("safety_rating");
                return account;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static TwitterAccount getAccountByScreenName(String screenName) {
        TwitterAccount account = new TwitterAccount();
        String sql = "SELECT twitter_id, artist_id, screen_name, display_name, account_status, is_protected, last_scraped_id, download_status, discord_thread_id, safety_rating " +
                "FROM twitter_accounts " +
                "WHERE screen_name = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1,screenName);
            try (ResultSet rs = ps.executeQuery()) {
                account.twitterId = rs.getString("twitter_id");
                account.artistId = rs.getInt("artist_id");
                account.screenName = rs.getString("screen_name");
                account.displayName = rs.getString("display_name");
                account.accountStatus = rs.getString("account_status");
                account.isProtected = rs.getBoolean("is_protected");
                account.lastScrapedId = rs.getString("last_scraped_id");
                account.downloadStatus = rs.getBoolean("download_status");
                account.discordThreadId = rs.getString("discord_thread_id");
                account.safetyRating = rs.getString("safety_rating");
                return account;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Registers a new account to the database with the provided details.
     *
     * @param twitterId
     * @param screenName
     * @param displayName
     * @param artistName
     * @param downloadStatus
     * @param accountSafetyRating
     * @return
     */
    public static CompletableFuture<String> registerAccount(String twitterId, String screenName, String displayName, String artistName, boolean downloadStatus, String accountSafetyRating) {
        if (twitterId == null || twitterId.isBlank()) return CompletableFuture.completedFuture("Error: Twitter ID is required.");
        if (screenName == null || screenName.isBlank()) return CompletableFuture.completedFuture("Error: Screen name is required.");
        if (artistName == null || artistName.isBlank()) return CompletableFuture.completedFuture("Error: Artist name is required.");
        if (!config.safetyRatings.contains(accountSafetyRating) && !accountSafetyRating.equals("Waiting")) {
            return CompletableFuture.completedFuture("Error: Invalid safety rating.");
        }

        twitterId = twitterId.trim();
        screenName = screenName.trim();
        artistName = artistName.trim();
        if (displayName != null) displayName = displayName.trim();

        String finalTwitterId = twitterId;
        String finalArtistName = artistName;
        String finalScreenName = screenName;
        String finalDisplayName = displayName;
        return writeQueue.runAsyncWriteWithResult(conn -> {
            String responseMessage = "";

            try {
                conn.setAutoCommit(false); // Start transaction
                // 1. Safe Check with Try-With-Resources for ResultSet
                String checkSql = "SELECT twitter_id FROM twitter_accounts WHERE twitter_id = ?";
                try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                    checkStmt.setString(1, finalTwitterId);
                    try (ResultSet rs = checkStmt.executeQuery()) {
                        if (rs.next()) {
                            conn.rollback(); // <--- CRITICAL FIX
                            return "Error: This Twitter account is already in the database.";
                        }
                    }
                }

                // 2. Safe Artist Resolve
                int artistId = -1;
                String artistCheckSql = "SELECT id FROM artists WHERE name = ?";
                try (PreparedStatement artistStmt = conn.prepareStatement(artistCheckSql)) {
                    artistStmt.setString(1, finalArtistName);
                    try (ResultSet rs = artistStmt.executeQuery()) {
                        if (rs.next()) {
                            artistId = rs.getInt("id");
                            responseMessage += "Found existing artist '" + finalArtistName + "'. Linking account... ";
                        }
                    }
                }

                // 3. Create Artist
                if (artistId == -1) {
                    String createArtistSql = "INSERT INTO artists (name, description) VALUES (?, ?)";
                    try (PreparedStatement createStmt = conn.prepareStatement(createArtistSql, Statement.RETURN_GENERATED_KEYS)) {
                        createStmt.setString(1, finalArtistName);
                        createStmt.setString(2, "No description added.");
                        createStmt.executeUpdate();

                        try (ResultSet generatedKeys = createStmt.getGeneratedKeys()) {
                            if (generatedKeys.next()) {
                                artistId = generatedKeys.getInt(1);
                                responseMessage += "Created new artist '" + finalArtistName + "'. ";
                            } else {
                                conn.rollback();
                                return "Error: Failed to create artist, no ID obtained.";
                            }
                        }
                    }
                }

                // 4. Create Account
                String insertAccountSql = "INSERT INTO twitter_accounts (twitter_id, artist_id, screen_name, display_name, account_status, download_status, safety_rating) VALUES (?, ?, ?, ?, 'Active', ?, ?)";
                try (PreparedStatement accStmt = conn.prepareStatement(insertAccountSql)) {
                    accStmt.setString(1, finalTwitterId);
                    accStmt.setInt(2, artistId);
                    accStmt.setString(3, finalScreenName);
                    accStmt.setString(4, finalDisplayName);
                    accStmt.setBoolean(5, downloadStatus);
                    accStmt.setString(6, accountSafetyRating);
                    accStmt.executeUpdate();
                }

                conn.commit();
                return responseMessage + "Account registered successfully.";

            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    throw new RuntimeException(ex);
                }
                e.printStackTrace();
                return "Error: Database failure during account registration.";
            }
        });
    }

    /**
     * Get the last stored scraped ID from the database.
     * @param twitterId
     * @return
     */
    public static String getLastScrapedId(String twitterId) {
        String sql = "SELECT last_scraped_id FROM twitter_accounts WHERE twitter_id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1,twitterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    // Returns the ID string, or null if the column is empty in the DB
                    return rs.getString("last_scraped_id");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    /**
     * Set the caption for the specified media by ID.
     * @param id
     * @param captionText
     */
    public static void setMediaCaption(String id, String captionText) {
        if (captionText == null) return;
        writeQueue.runAsyncWrite(conn -> {
            String sql = "UPDATE media SET caption = ? WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, captionText);
                ps.setString(2, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Creates database with required schema at the location specified in the config.
     */
    public static void createDatabase() {
        System.out.println("WELCOME TO SANDSTAR ARCHIVE. Your database is being created at " + config.databasePath);
        try (Connection conn = getConnection(); Statement statement = conn.createStatement()) {
            //enable foreign keys
            statement.execute("PRAGMA journal_mode = WAL;");
            statement.execute("PRAGMA foreign_keys = ON;");
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS artists (\n" +
                            "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                            "    name TEXT NOT NULL COLLATE NOCASE,\n" +
                            "    description TEXT\n" +
                            ");");
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS aliases (\n" +
                            "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                            "    artist_id INTEGER NOT NULL,\n" +
                            "    alias_name TEXT NOT NULL COLLATE NOCASE,\n" +
                            "    safety_rating TEXT,\n" +
                            "    FOREIGN KEY (artist_id) REFERENCES artists(id) ON DELETE CASCADE\n" +
                            ");");
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS twitter_accounts (\n" +
                            "    twitter_id TEXT PRIMARY KEY,\n" +
                            "    artist_id INTEGER NOT NULL,\n" +
                            "    screen_name TEXT NOT NULL COLLATE NOCASE,\n" +
                            "    display_name TEXT,\n" +
                            "    account_status TEXT,\n" +
                            "    is_protected INTEGER DEFAULT 0,\n" +
                            "    last_scraped_id TEXT,\n" +
                            "    download_status INTEGER DEFAULT 1,\n" +
                            "    discord_thread_id TEXT,\n" +
                            "    safety_rating TEXT,\n" +
                            "    FOREIGN KEY (artist_id) REFERENCES artists(id) ON DELETE CASCADE\n" +
                            ");");
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS posts (\n" +
                            "    post_id TEXT PRIMARY KEY,\n" +
                            "    twitter_id TEXT NOT NULL,\n" +
                            "    post_text TEXT,\n" +
                            "    post_date INTEGER,\n" +
                            "    archive_date INTEGER,\n" +
                            "    safety_rating TEXT,\n" +
                            "    content_rating INTEGER,\n" +
                            "    FOREIGN KEY (twitter_id) REFERENCES twitter_accounts(twitter_id) ON DELETE CASCADE\n" +
                            ");");
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS media (\n" +
                            "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                            "    post_id TEXT NOT NULL,\n" +
                            "    media_type TEXT,\n" +
                            "    original_url TEXT,\n" +
                            "    local_path TEXT,\n" +
                            "    caption TEXT,\n" +
                            "    safety_rating TEXT,\n" +
                            "    content_rating TEXT,\n" +
                            "    media_index INTEGER,\n" +
                            "    perceptual_hash TEXT,\n" +
                            "    data_hash TEXT,\n" +
                            "    duplicate_of INTEGER,\n" +
                            "    width INTEGER,\n" +
                            "    height INTEGER,\n" +
                            "    filesize INTEGER,\n" +
                            "    FOREIGN KEY (post_id) REFERENCES posts(post_id) ON DELETE CASCADE,\n" +
                            "    FOREIGN KEY (duplicate_of) REFERENCES media(id) ON DELETE SET NULL\n" +
                            ");");
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS users(\n" +
                            "   id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                            "   username TEXT UNIQUE COLLATE NOCASE NOT NULL,\n" +
                            "   email TEXT UNIQUE COLLATE NOCASE,\n" + //email is optional
                            "   password_hash TEXT NOT NULL,\n" +
                            "   role TEXT NOT NULL DEFAULT 'Read',\n" +
                            "   restriction_level TEXT DEFAULT 'None',\n" +
                            "   banned INTEGER NOT NULL DEFAULT 0,\n" +
                            "   invite_key_used TEXT,\n" +
                            "   note TEXT,\n" +
                            "   about_me TEXT,\n" +
                            "   creation_date INTEGER NOT NULL\n" +
                            ");");
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS invite_keys(\n" +
                            "   id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                            "   invite_key TEXT UNIQUE NOT NULL,\n" +
                            "   grant_role TEXT NOT NULL DEFAULT 'Read',\n" +
                            "   max_uses INTEGER NOT NULL DEFAULT 1,\n" + //only 1 use by default
                            "   times_used INTEGER NOT NULL DEFAULT 0,\n" +
                            "   expires_at INTEGER,\n" + //-1 never expires
                            "   created_by_user_id INTEGER,\n" +
                            "   creation_date INTEGER NOT NULL,\n" +
                            "   FOREIGN KEY (created_by_user_id) REFERENCES users(id)\n" +
                            ");");
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS sessions (\n" +
                            "    session_token TEXT PRIMARY KEY,\n" +
                            "    user_id INTEGER NOT NULL,\n" +
                            "    token_type TEXT NOT NULL DEFAULT 'User',\n" + // 'User' or 'Bot'
                            "    created_at INTEGER NOT NULL,\n" +
                            "    expires_at INTEGER,\n" + // NULL means never expires
                            "    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE\n" +
                            ");");
            statement.execute(
                    "CREATE INDEX IF NOT EXISTS idx_post_twitter_id ON posts(twitter_id);\n" +
                            "CREATE INDEX IF NOT EXISTS idx_media_post_id ON media(post_id);\n" +
                            "CREATE INDEX IF NOT EXISTS idx_media_data_hash ON media(data_hash);\n" +
                            "CREATE INDEX IF NOT EXISTS idx_media_p_hash ON media(perceptual_hash);\n" +
                            "CREATE INDEX IF NOT EXISTS idx_sessions_token ON sessions(session_token);");

            InviteKey initialKey = generateKey(Permission.EXECUTE,2,-1,null);

            System.out.println("DATABASE CREATED! You now need to use the web server to register your account.\n" +
                    "Your registration key is [" + initialKey.inviteKey + "]. This key has 2 uses, and never expires.\n" +
                    "It will grant you Execute permissions to manage your archive.");
        } catch (SQLException e) {
            System.out.println("Database creation failed. Check if .db file was created, delete it, and try again.");
            throw new RuntimeException(e);
        }
    }

    /**
     * Method that updates safety/content ratings in media and posts table, moves files to their proper location, and updates filepath known to database. Allows null for content and safety rating.
     * @param postId
     * @param newContent
     * @param newSafety
     */
    public static void setPostRatings(String postId, String newContent, String newSafety) {
        //null is allowed but "Waiting" is explicitly not allowed, and any string that is not in the list of safety and content ratings is not allowed
        if (newContent != null) {
            if (newContent.equalsIgnoreCase("Waiting")) {
                System.err.println("[Validation] Blocking attempt to set Content Rating to 'Waiting' for post: " + postId);
                return;
            }
            if (!config.contentRatings.contains(newContent)) {
                System.err.println("[Validation] Invalid Content Rating '" + newContent + "' submitted for post: " + postId);
                return;
            }
        }
        if (newSafety != null) {
            if (newSafety.equalsIgnoreCase("Waiting")) {
                System.err.println("[Validation] Blocking attempt to set Safety Rating to 'Waiting' for post: " + postId);
                return;
            }
            if (!config.safetyRatings.contains(newSafety)) {
                System.err.println("[Validation] Invalid Safety Rating '" + newSafety + "' submitted for post: " + postId);
                return;
            }
        }
        //if both ratings are null, just exit
        if (newContent == null && newSafety == null) return;
        writeQueue.runAsyncWrite(conn -> {
            try {
                // 1. Fetch current Post record to handle null parameters
                String currentPostContent, currentPostSafety;
                try (PreparedStatement ps = conn.prepareStatement("SELECT content_rating, safety_rating FROM posts WHERE post_id = ?")) {
                    ps.setString(1, postId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        currentPostContent = rs.getString("content_rating");
                        currentPostSafety = rs.getString("safety_rating");
                    } else {
                        System.err.println("[Error] Attempted to update ratings for non-existent post: " + postId);
                        return;
                    }
                }

                // Determine what the new ratings should be (Keep current if parameter is null)
                String finalContent = (newContent != null) ? newContent : currentPostContent;
                String finalSafety = (newSafety != null) ? newSafety : currentPostSafety;

                // If absolutely nothing is changing, exit early to save IO
                if (finalContent.equals(currentPostContent) && finalSafety.equals(currentPostSafety)) return;

                // 2. Fetch all media associated with this post
                List<MediaRecord> mediaFiles = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement("SELECT id, local_path FROM media WHERE post_id = ?")) {
                    ps.setString(1, postId);
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        mediaFiles.add(new MediaRecord(rs.getInt("id"), rs.getString("local_path")));
                    }
                }

                // Prepare the target directory
                Path newDir = Paths.get(config.imageDownloadPath, finalContent, finalSafety);
                Files.createDirectories(newDir);

                for (MediaRecord media : mediaFiles) {
                    // Determine target path
                    Path oldPath = (media.localPath != null) ? Paths.get(media.localPath) : null;
                    String fileName = (oldPath != null) ? oldPath.getFileName().toString() : "media_" + media.id;
                    Path destination = newDir.resolve(fileName);

                    boolean moveSuccessful = false;

                    // --- AGGRESSIVE FILE CHECKS ---
                    if (oldPath != null && Files.exists(oldPath)) {
                        // Scenario A: File is where the database says it is.
                        if (!oldPath.equals(destination)) {
                            Files.move(oldPath, destination, StandardCopyOption.REPLACE_EXISTING);
                        }
                        moveSuccessful = true;
                    }
                    else if (Files.exists(destination)) {
                        // Scenario B: File is already at the destination (perhaps from a previous failed run)
                        moveSuccessful = true;
                    }
                    else {
                        // Scenario C: File is missing from both locations.
                        System.err.println("[Warning] Media file missing for ID " + media.id + ". Expected: " + oldPath);
                        // We don't return here; we still update the DB ratings so metadata stays in sync.
                    }

                    // 3. Update individual Media Row (Ratings + Path)
                    // We propagate the Post rating down to the Media rating here.
                    String updateMediaSql = "UPDATE media SET content_rating = ?, safety_rating = ?, local_path = ? WHERE id = ?";
                    try (PreparedStatement ps = conn.prepareStatement(updateMediaSql)) {
                        ps.setString(1, finalContent);
                        ps.setString(2, finalSafety);
                        ps.setString(3, moveSuccessful ? destination.toString() : media.localPath);
                        ps.setInt(4, media.id);
                        ps.executeUpdate();
                    }
                }

                // 4. Update the Main Post Table
                try (PreparedStatement ps = conn.prepareStatement("UPDATE posts SET content_rating = ?, safety_rating = ? WHERE post_id = ?")) {
                    ps.setString(1, finalContent);
                    ps.setString(2, finalSafety);
                    ps.setString(3, postId);
                    ps.executeUpdate();
                }
                // System.out.println("[Success] Updated Post " + postId + " to " + finalContent + "/" + finalSafety);
            } catch (Exception e) {
                System.err.println("[Critical Error] Failed to set ratings for post " + postId);
                e.printStackTrace();
            }
        });
    }

    /**
     * Updates ratings for a specific piece of media, moves its file, and updates its path.
     * Allows null for newContent or newSafety to maintain current values.
     */
    public static void setMediaRatings(int mediaId, String newContent, String newSafety) {
        //null is allowed but "Waiting" is explicitly not allowed, and any string that is not in the list of safety and content ratings is not allowed
        if (newContent != null) {
            if (newContent.equalsIgnoreCase("Waiting")) {
                System.err.println("[Validation] Blocking attempt to set Content Rating to 'Waiting' for media: " + mediaId);
                return;
            }
            if (!config.contentRatings.contains(newContent)) {
                System.err.println("[Validation] Invalid Content Rating '" + newContent + "' submitted for media: " + mediaId);
                return;
            }
        }
        if (newSafety != null) {
            if (newSafety.equalsIgnoreCase("Waiting")) {
                System.err.println("[Validation] Blocking attempt to set Safety Rating to 'Waiting' for media: " + mediaId);
                return;
            }
            if (!config.safetyRatings.contains(newSafety)) {
                System.err.println("[Validation] Invalid Safety Rating '" + newSafety + "' submitted for post: " + mediaId);
                return;
            }
        }
        //if both ratings are null, just exit
        if (newContent == null && newSafety == null) return;
        writeQueue.runAsyncWrite(conn -> {
            try {
                // 1. Fetch current media record
                String currentContent, currentSafety, currentPath;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT content_rating, safety_rating, local_path FROM media WHERE id = ?")) {
                    ps.setInt(1, mediaId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        currentContent = rs.getString("content_rating");
                        currentSafety = rs.getString("safety_rating");
                        currentPath = rs.getString("local_path");
                    } else {
                        System.err.println("[Error] Attempted to update non-existent media ID: " + mediaId);
                        return;
                    }
                }

                // Fallback: If media columns were null (newly migrated), get default from its parent Post
                if (currentContent == null || currentSafety == null) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT content_rating, safety_rating FROM posts WHERE post_id = " +
                                    "(SELECT post_id FROM media WHERE id = ?)")) {
                        ps.setInt(1, mediaId);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            if (currentContent == null) currentContent = rs.getString("content_rating");
                            if (currentSafety == null) currentSafety = rs.getString("safety_rating");
                        }
                    }
                }

                // Determine final ratings
                String finalContent = (newContent != null) ? newContent : currentContent;
                String finalSafety = (newSafety != null) ? newSafety : currentSafety;

                // Exit if nothing is changing
                if (finalContent.equals(currentContent) && finalSafety.equals(currentSafety)) return;

                // 2. Resolve Paths
                if (currentPath == null || currentPath.isEmpty()) {
                    System.err.println("[Error] Cannot move media ID " + mediaId + " - No path stored in DB.");
                    return;
                }

                Path oldPath = Paths.get(currentPath);
                Path newDir = Paths.get(config.imageDownloadPath, finalContent, finalSafety);
                Files.createDirectories(newDir);
                Path destination = newDir.resolve(oldPath.getFileName());

                boolean moveSuccessful = false;

                // 3. Defensive File Move
                if (Files.exists(oldPath)) {
                    if (!oldPath.equals(destination)) {
                        Files.move(oldPath, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                    moveSuccessful = true;
                } else if (Files.exists(destination)) {
                    // Already at destination
                    moveSuccessful = true;
                } else {
                    System.err.println("[Warning] Media file missing for ID " + mediaId + " at " + currentPath);
                }

                // 4. Update the Media Row
                String sql = "UPDATE media SET content_rating = ?, safety_rating = ?, local_path = ? WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, finalContent);
                    ps.setString(2, finalSafety);
                    ps.setString(3, moveSuccessful ? destination.toString() : currentPath);
                    ps.setInt(4, mediaId);
                    ps.executeUpdate();
                }
            } catch (Exception e) {
                System.err.println("[Critical Error] Failed to update media ID: " + mediaId);
                e.printStackTrace();
            }
        });
    }

    private static class MediaRecord {
        int id;
        String localPath;
        MediaRecord(int id, String localPath) { this.id = id; this.localPath = localPath; }
    }
    /*
    public static List<TwitterPost> getPostsByUserIdPaged(String twitterId, int limit, int offset) {
        List<TwitterPost> posts = new ArrayList<>();
        String sql = "SELECT p.post_id, p.twitter_id, p.post_text, p.post_date, p.archive_date, " +
                "p.safety_rating AS p_safety, p.content_rating AS p_content, " +
                "m.id, m.media_type, m.original_url, m.local_path, m.caption, m.media_index, " +
                "m.perceptual_hash, m.data_hash, m.duplicate_of, m.width, m.height, m.filesize, " +
                "m.safety_rating AS m_safety, m.content_rating AS m_content " +
                "FROM (SELECT * FROM posts WHERE twitter_id = ? ORDER BY post_date DESC LIMIT ? OFFSET ?) p " +
                "LEFT JOIN media m ON p.post_id = m.post_id " +
                "ORDER BY p.post_date DESC, m.media_index ASC";

        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, twitterId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);

            try (ResultSet rs = ps.executeQuery()) {
                TwitterPost currentPost = null;
                while (rs.next()) {
                    String rowPostId = rs.getString("post_id");
                    if (currentPost == null || !currentPost.postId.equals(rowPostId)) {
                        currentPost = new TwitterPost();
                        currentPost.postId = rowPostId;
                        currentPost.twitterId = rs.getString("twitter_id");
                        currentPost.postText = rs.getString("post_text");
                        currentPost.postDate = rs.getLong("post_date");
                        currentPost.archiveDate = rs.getLong("archive_date");
                        currentPost.safetyRating = rs.getString("p_safety");
                        currentPost.contentRating = rs.getString("p_content");
                        currentPost.media = new ArrayList<>();
                        posts.add(currentPost);
                    }
                    int mediaId = rs.getInt("id");
                    if (mediaId != 0) {
                        TwitterMedia m = new TwitterMedia();
                        m.id = mediaId;
                        m.postId = rowPostId;
                        m.localPath = rs.getString("local_path");
                        m.mediaType = rs.getString("media_type");
                        m.safetyRating = rs.getString("m_safety");
                        m.contentRating = rs.getString("m_content");
                        currentPost.media.add(m);
                    }
                }
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return posts;
    }*/

    public static List<Artist> searchArtists(String query) {
        List<Artist> results = new ArrayList<>();
        // Search by artist name OR alias name
        String sql = "SELECT DISTINCT a.id, a.name, a.description FROM artists a " +
                "LEFT JOIN aliases al ON a.id = al.artist_id " +
                "WHERE a.name LIKE ? OR al.alias_name LIKE ?" +
                "ORDER BY a.name ASC";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            String q = "%" + query + "%";
            ps.setString(1, q);
            ps.setString(2, q);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new Artist(rs.getInt("id"), rs.getString("name"), rs.getString("description")));
                }
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return results;
    }

    public static ArtistDetails getArtistDetailsById(int artistId) {
        ArtistDetails details = new ArtistDetails();
        try (Connection conn = getConnection()) {
            // 1. Get Artist
            try (PreparedStatement ps = conn.prepareStatement("SELECT id, name, description FROM artists WHERE id = ?")) {
                ps.setInt(1, artistId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    details.id = rs.getInt("id");
                    details.name = rs.getString("name");
                    details.description = rs.getString("description");
                }
            }
            // 2. Get Aliases
            try (PreparedStatement ps = conn.prepareStatement("SELECT id, artist_id, alias_name, safety_rating FROM aliases WHERE artist_id = ?")) {
                ps.setInt(1, artistId);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    details.aliases.add(new Alias(
                            rs.getInt("id"),
                            rs.getInt("artist_id"),
                            rs.getString("alias_name"),
                            rs.getString("safety_rating")
                            ));
                }
            }
            // 3. Get Accounts
            try (PreparedStatement ps = conn.prepareStatement("SELECT twitter_id, artist_id, screen_name, display_name, account_status, is_protected, last_scraped_id, download_status, discord_thread_id, safety_rating FROM twitter_accounts WHERE artist_id = ?")) {
                ps.setInt(1, artistId);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    TwitterAccount acc = new TwitterAccount();
                    acc.twitterId = rs.getString("twitter_id");
                    acc.artistId = rs.getInt("artist_id");
                    acc.screenName = rs.getString("screen_name");
                    acc.displayName = rs.getString("display_name");
                    acc.accountStatus = rs.getString("account_status");
                    acc.isProtected = rs.getBoolean("is_protected");
                    acc.lastScrapedId = rs.getString("last_scraped_id");
                    acc.downloadStatus = rs.getBoolean("download_status");
                    acc.discordThreadId = rs.getString("discord_thread_id");
                    acc.safetyRating = rs.getString("safety_rating");
                    details.accounts.add(acc);
                }
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return details;
    }

    public static List<TwitterAccount> searchAccounts(String query) {
        List<TwitterAccount> results = new ArrayList<>();
        // Search by artist name OR alias name
        String sql = "SELECT twitter_id, artist_id, screen_name, display_name, account_status, is_protected, last_scraped_id, download_status, discord_thread_id, safety_rating FROM twitter_accounts WHERE screen_name LIKE ? OR display_name LIKE ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            String q = "%" + query + "%";
            ps.setString(1, q);
            ps.setString(2, q);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TwitterAccount acc = new TwitterAccount();
                    acc.twitterId = rs.getString("twitter_id");
                    acc.artistId = rs.getInt("artist_id");
                    acc.screenName = rs.getString("screen_name");
                    acc.displayName = rs.getString("display_name");
                    acc.accountStatus = rs.getString("account_status");
                    acc.isProtected = rs.getBoolean("is_protected");
                    acc.lastScrapedId = rs.getString("last_scraped_id");
                    acc.downloadStatus = rs.getBoolean("download_status");
                    acc.discordThreadId = rs.getString("discord_thread_id");
                    acc.safetyRating = rs.getString("safety_rating");
                    results.add(acc);
                }
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return results;
    }

    public static TwitterMedia getMediaById(int id) {
        String sql = "SELECT * FROM media WHERE id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    TwitterMedia m = new TwitterMedia();
                    m.id = rs.getInt("id");
                    m.postId = rs.getString("post_id");
                    m.mediaType = rs.getString("media_type");
                    m.originalUrl = rs.getString("original_url");
                    m.localPath = rs.getString("local_path");
                    m.caption = rs.getString("caption");
                    m.mediaIndex = rs.getInt("media_index");
                    m.perceptualHash = rs.getString("perceptual_hash");
                    m.dataHash = rs.getString("data_hash");
                    m.duplicateOf = rs.getInt("duplicate_of");
                    m.width = rs.getInt("width");
                    m.height = rs.getInt("height");
                    m.filesize = rs.getLong("filesize");
                    m.contentRating = rs.getString("content_rating");
                    m.safetyRating = rs.getString("safety_rating");
                    return m;
                }
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return null;
    }

    public static TwitterPost getPostDetails(String postId) {
        TwitterPost post = null;
        String sql = "SELECT p.*, " +
                "m.id AS m_id, m.media_type, m.original_url, m.local_path, m.caption, " +
                "m.safety_rating AS m_safety, m.content_rating AS m_content, m.media_index, " +
                "m.width, m.height, m.filesize " +
                "FROM posts p " +
                "LEFT JOIN media m ON p.post_id = m.post_id " +
                "WHERE p.post_id = ? " +
                "ORDER BY m.media_index ASC";

        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // Initialize the post object once using data from the first row
                    if (post == null) {
                        post = new TwitterPost();
                        post.postId = rs.getString("post_id");
                        post.twitterId = rs.getString("twitter_id");
                        post.postText = rs.getString("post_text");
                        post.postDate = rs.getLong("post_date"); // Use getLong for timestamps
                        post.archiveDate = rs.getLong("archive_date");
                        post.safetyRating = rs.getString("safety_rating");
                        post.contentRating = rs.getString("content_rating");
                        post.media = new ArrayList<>();
                    }

                    // Check if this row contains a piece of media (m_id will be 0 if no media exists due to LEFT JOIN)
                    int mediaId = rs.getInt("m_id");
                    if (mediaId != 0) {
                        TwitterMedia m = new TwitterMedia();
                        m.id = mediaId;
                        m.postId = post.postId;
                        m.mediaType = rs.getString("media_type");
                        m.originalUrl = rs.getString("original_url");
                        m.localPath = rs.getString("local_path");
                        m.caption = rs.getString("caption");
                        m.safetyRating = rs.getString("m_safety");
                        m.contentRating = rs.getString("m_content");
                        m.mediaIndex = rs.getInt("media_index");
                        m.width = rs.getInt("width");
                        m.height = rs.getInt("height");
                        m.filesize = rs.getLong("filesize");

                        post.media.add(m);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error fetching details for post: " + postId, e);
        }
        return post;
    }

    public static ArtistDetails getArtistDetailsByName(String name) {
        int id = -1;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id FROM artists WHERE name = ?")) {
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) id = rs.getInt("id");
        } catch (SQLException e) { throw new RuntimeException(e); }

        return id != -1 ? getArtistDetailsById(id) : null;
    }

    public static List<TwitterPost> getGlobalPostsPaged(int limit, int offset, List<String> contentFilters, List<String> safetyFilters, String sort) {
        return getFilteredPosts(null, limit, offset, contentFilters, safetyFilters, sort);
    }

    public static List<TwitterPost> getPostsByUserIdPaged(String twitterId, int limit, int offset, List<String> contentFilters, List<String> safetyFilters, String sort) {
        return getFilteredPosts(twitterId, limit, offset, contentFilters, safetyFilters, sort);
    }

    private static List<TwitterPost> getFilteredPosts(String twitterId, int limit, int offset, List<String> contentFilters, List<String> safetyFilters, String sort) {
        List<TwitterPost> posts = new ArrayList<>();
        StringBuilder subquery = new StringBuilder("SELECT * FROM posts WHERE 1=1");
        List<Object> params = new ArrayList<>();

        // Handle specific account targeting
        if (twitterId != null) {
            subquery.append(" AND twitter_id = ?");
            params.add(twitterId);
        }

        // Handle Content Filters (If empty, we append NOTHING, thereby returning ALL ratings)
        if (contentFilters != null && !contentFilters.isEmpty()) {
            subquery.append(" AND content_rating IN (");
            for (int i = 0; i < contentFilters.size(); i++) {
                subquery.append(i == 0 ? "?" : ", ?");
                params.add(contentFilters.get(i));
            }
            subquery.append(")");
        }

        // Handle Safety Filters (If empty, we append NOTHING, thereby returning ALL ratings)
        if (safetyFilters != null && !safetyFilters.isEmpty()) {
            subquery.append(" AND safety_rating IN (");
            for (int i = 0; i < safetyFilters.size(); i++) {
                subquery.append(i == 0 ? "?" : ", ?");
                params.add(safetyFilters.get(i));
            }
            subquery.append(")");
        }

        // Determine sort order
        String innerOrder = "post_date DESC"; // Default Newest
        String outerOrder = "p.post_date DESC, m.media_index ASC";

        if ("oldest".equalsIgnoreCase(sort)) {
            innerOrder = "post_date ASC";
            outerOrder = "p.post_date ASC, m.media_index ASC";
        } else if ("random".equalsIgnoreCase(sort)) {
            innerOrder = "RANDOM()";
            // Important: keep the media grouped by post when selecting randomly, but don't force them back into chronological order
            outerOrder = "p.post_id, m.media_index ASC";
        }

        subquery.append(" ORDER BY ").append(innerOrder).append(" LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        // Ensure LIMIT/OFFSET remains in the subquery to properly limit amount of POSTS, not MEDIA.
        String sql = "SELECT p.*, a.screen_name, " +
                "m.id AS m_id, m.media_type, m.original_url, m.local_path, m.content_rating AS m_content, m.safety_rating AS m_safety " +
                "FROM (" + subquery.toString() + ") p " +
                "JOIN twitter_accounts a ON p.twitter_id = a.twitter_id " +
                "LEFT JOIN media m ON p.post_id = m.post_id " +
                "ORDER BY " + outerOrder;

        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            // Parameter setting
            for (int i = 0; i < params.size(); i++) {
                Object param = params.get(i);
                if (param instanceof String) {
                    ps.setString(i + 1, (String) param);
                } else if (param instanceof Integer) {
                    ps.setInt(i + 1, (Integer) param);
                }
            }

            try (ResultSet rs = ps.executeQuery()) {
                TwitterPost currentPost = null;
                while (rs.next()) {
                    String rowPostId = rs.getString("post_id");
                    if (currentPost == null || !currentPost.postId.equals(rowPostId)) {
                        currentPost = new TwitterPost();
                        currentPost.postId = rowPostId;
                        currentPost.screenName = rs.getString("screen_name");
                        currentPost.twitterId = rs.getString("twitter_id");
                        currentPost.postText = rs.getString("post_text");
                        currentPost.postDate = rs.getLong("post_date");
                        currentPost.archiveDate = rs.getLong("archive_date");
                        currentPost.safetyRating = rs.getString("safety_rating");
                        currentPost.contentRating = rs.getString("content_rating");
                        currentPost.media = new ArrayList<>();
                        posts.add(currentPost);
                    }
                    int mId = rs.getInt("m_id");
                    if (mId != 0) {
                        TwitterMedia m = new TwitterMedia();
                        m.id = mId;
                        m.originalUrl = rs.getString("original_url");
                        m.localPath = rs.getString("local_path");
                        m.contentRating = rs.getString("m_content");
                        m.safetyRating = rs.getString("m_safety");
                        m.mediaType = rs.getString("media_type");
                        currentPost.media.add(m);
                    }
                }
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return posts;
    }

    /**
     * Add a new alias name for an artist by name. Requires setting safety rating of the alias name.
     * @param artistName
     * @param newAliasName
     * @param safetyRating
     * @return
     */
    public static String addAlias(String artistName, String newAliasName, String safetyRating) {
        if (artistName == null || artistName.isBlank()) return "Error: Artist name is required.";
        if (newAliasName == null || newAliasName.isBlank()) return "Error: Alias name cannot be blank.";
        if (!config.safetyRatings.contains(safetyRating) && !safetyRating.equals("Waiting")) return "Error: Invalid safety rating.";

        newAliasName = newAliasName.trim();
        artistName = artistName.trim();

        ArtistDetails artist = getArtistDetailsByName(artistName);
        if (artist == null) {
            return "Error: Artist '" + artistName + "' not found.";
        }

        // Run synchronously so we can return the actual result
        String sql = "INSERT INTO aliases (artist_id, alias_name, safety_rating) VALUES (?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, artist.id);
            ps.setString(2, newAliasName);
            ps.setString(3, safetyRating);
            ps.executeUpdate();
            return "Added alias name '" + newAliasName  + "' for artist '" + artistName + "'.";
        } catch (SQLException e) {
            if (e.getMessage().toLowerCase().contains("unique")) {
                return "Error: This alias already exists.";
            }
            e.printStackTrace();
            return "Error: Failed to save alias to the database.";
        }
    }

    public static List<Artist> getAllArtists() {
        List<Artist> results = new ArrayList<>();
        String sql = "SELECT * FROM artists ORDER BY name ASC";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(new Artist(
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getString("description"))
                );
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return results;
    }

    public static void setArtistDescriptionByName(String artistName, String description) {
        if (artistName == null || description == null) return;
        writeQueue.runAsyncWrite(conn -> {
            String sql = "UPDATE artists SET description = ? WHERE name = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, description);
                ps.setString(2, artistName);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Gets a user by EITHER their username or their email. Used for logging in (returns hashed password).
     */
    public static ArchiveUser getUserByIdentifier(String identifier) {
        String sql = "SELECT * FROM users WHERE username = ? OR email = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, identifier);
            ps.setString(2, identifier);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ArchiveUser u = new ArchiveUser();
                    u.id = rs.getInt("id");
                    u.username = rs.getString("username");
                    u.email = rs.getString("email");
                    u.passwordHash = rs.getString("password_hash");
                    u.role = rs.getString("role");
                    u.restrictionLevel = rs.getString("restriction_level");
                    u.banned = rs.getBoolean("banned");
                    return u;
                }
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return null;
    }

    /**
     * Handles completely registering a user, checking invite keys, and hashing the password.
     */
    public static String registerUser(String username, String email, String rawPassword, String inviteKeyStr) {
        // 1. Strict Sanitation
        if (inviteKeyStr == null || inviteKeyStr.isBlank()) return "Error: An invite key is required to register.";
        if (username == null || username.isBlank()) return "Error: Username cannot be empty.";
        if (rawPassword == null || rawPassword.isBlank()) return "Error: Password cannot be empty.";

        username = username.trim();
        if (email != null) email = email.trim();
        inviteKeyStr = inviteKeyStr.trim();

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false); // Start transaction

            try {
                String assignedRole;

                // 2. Validate Invite Key
                String keySql = "SELECT id, max_uses, times_used, expires_at, grant_role FROM invite_keys WHERE invite_key = ?";
                try (PreparedStatement ps = conn.prepareStatement(keySql)) {
                    ps.setString(1, inviteKeyStr);
                    ResultSet rs = ps.executeQuery();

                    if (!rs.next()) return "Error: Invalid invite key.";

                    int id = rs.getInt("id");
                    int maxUses = rs.getInt("max_uses");
                    int timesUsed = rs.getInt("times_used");
                    long expiresAt = rs.getLong("expires_at");

                    if (maxUses != -1 && timesUsed >= maxUses) return "Error: Invite key has no uses left.";
                    if (expiresAt != -1 && System.currentTimeMillis() > expiresAt) return "Error: Invite key has expired.";

                    assignedRole = rs.getString("grant_role");

                    // Increment key usage
                    try (PreparedStatement updateKey = conn.prepareStatement("UPDATE invite_keys SET times_used = times_used + 1 WHERE id = ?")) {
                        updateKey.setInt(1, id);
                        updateKey.executeUpdate();
                    }
                }

                // 3. Hash Password and Insert User
                String hashedPassword = org.mindrot.jbcrypt.BCrypt.hashpw(rawPassword, org.mindrot.jbcrypt.BCrypt.gensalt(12));
                String insertUserSql = "INSERT INTO users (username, email, password_hash, role, invite_key_used, creation_date) VALUES (?, ?, ?, ?, ?, ?)";

                try (PreparedStatement ps = conn.prepareStatement(insertUserSql)) {
                    ps.setString(1, username);
                    if (email == null || email.isEmpty()) ps.setNull(2, java.sql.Types.VARCHAR);
                    else ps.setString(2, email);

                    ps.setString(3, hashedPassword);
                    ps.setString(4, assignedRole);
                    ps.setString(5, inviteKeyStr);
                    ps.setLong(6, System.currentTimeMillis());

                    ps.executeUpdate();
                }

                conn.commit();
                return "Success";

            } catch (SQLException e) {
                conn.rollback(); // Undo invite key increment if user creation fails
                if (e.getMessage().toLowerCase().contains("unique")) {
                    return "Error: Username or Email is already taken.";
                }
                e.printStackTrace();
                return "Error: Database failure during user registration.";
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Generate a new invite key with the specified values.
     */
    public static InviteKey generateKey(String grantRole, int maxUses, long expiresAt, Integer creatorUserId) {
        if (grantRole.equals(Permission.READ) || grantRole.equals(Permission.WRITE) || grantRole.equals(Permission.EXECUTE)) {
            if (maxUses == 0) maxUses = -1;
            if (expiresAt == 0) expiresAt = -1;

            InviteKey newKey = new InviteKey();
            newKey.grantRole = grantRole;
            newKey.maxUses = maxUses;
            newKey.expiresAt = expiresAt;
            newKey.createdByUserId = (creatorUserId != null) ? creatorUserId : 0; // 0 represents System internally
            newKey.creationDate = Instant.now(Clock.systemUTC()).getEpochSecond();

            // 1. Generate the 7-character string
            StringBuilder sb = new StringBuilder(7);
            for (int i = 0; i < 7; i++) {
                sb.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
            }
            newKey.inviteKey = sb.toString();

            // 2. Insert into the database
            writeQueue.runAsyncWrite(conn -> {
                String sql = "INSERT INTO invite_keys (invite_key, grant_role, max_uses, expires_at, created_by_user_id, creation_date) VALUES (?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, newKey.inviteKey);
                    ps.setString(2, newKey.grantRole);
                    ps.setInt(3, newKey.maxUses);
                    ps.setLong(4, newKey.expiresAt);

                    // Prevent Foreign Key crash by inserting literal NULL if no user exists
                    if (creatorUserId == null) {
                        ps.setNull(5, java.sql.Types.INTEGER);
                    } else {
                        ps.setInt(5, creatorUserId);
                    }

                    ps.setLong(6, newKey.creationDate);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });
            return newKey;
        } else {
            System.err.println("Invalid role for invite key creation. No key generated.");
            return null;
        }
    }

    public static List<InviteKey> getKeysPaged(int limit, int offset) {
        List<InviteKey> keys = new ArrayList<>();
        String sql = "SELECT * FROM invite_keys ORDER BY creation_date DESC LIMIT ? OFFSET ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    InviteKey k = new InviteKey();
                    k.id = rs.getInt("id");
                    k.inviteKey = rs.getString("invite_key");
                    k.grantRole = rs.getString("grant_role");
                    k.maxUses = rs.getInt("max_uses");
                    k.timesUsed = rs.getInt("times_used");
                    k.expiresAt = rs.getLong("expires_at");
                    k.createdByUserId = rs.getInt("created_by_user_id");
                    k.creationDate = rs.getLong("creation_date");
                    keys.add(k);
                }
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return keys;
    }

    public static void updateKey(int id, String newRole, int newMaxUses, long newExpiresAt) {
        writeQueue.runAsyncWrite(conn -> {
            String sql = "UPDATE invite_keys SET grant_role = ?, max_uses = ?, expires_at = ? WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, newRole);
                ps.setInt(2, newMaxUses);
                ps.setLong(3, newExpiresAt);
                ps.setInt(4, id);
                ps.executeUpdate();
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    public static void deleteKey(int id) {
        writeQueue.runAsyncWrite(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM invite_keys WHERE id = ?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    /**
     * Get a (safe) paginated list of users. Safe, because it does not get email or password hash.
     */
    public static List<ArchiveUser> getUsersPaged(int limit, int offset) {
        List<ArchiveUser> users = new ArrayList<>();
        // Explicitly SELECT only the safe columns. Do NOT use SELECT *
        String sql = "SELECT id, username, role, restriction_level, banned, invite_key_used, note, creation_date " +
                "FROM users ORDER BY creation_date DESC LIMIT ? OFFSET ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ArchiveUser u = new ArchiveUser();
                    u.id = rs.getInt("id");
                    u.username = rs.getString("username");
                    u.role = rs.getString("role");
                    u.restrictionLevel = rs.getString("restriction_level");
                    u.banned = rs.getBoolean("banned");
                    u.inviteKeyUsed = rs.getString("invite_key_used");
                    u.note = rs.getString("note");
                    u.creationDate = rs.getLong("creation_date");
                    users.add(u);
                }
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return users;
    }

    public static void updateUserAdmin(int id, String newRole, boolean isBanned, String newNote) {
        writeQueue.runAsyncWrite(conn -> {
            String sql = "UPDATE users SET role = ?, banned = ?, note = ? WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, newRole);
                ps.setBoolean(2, isBanned);
                ps.setString(3, newNote);
                ps.setInt(4, id);
                ps.executeUpdate();
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    public static ArchiveUser getUserProfile(int id) {
        String sql = "SELECT id, username, email, about_me, role, creation_date FROM users WHERE id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ArchiveUser u = new ArchiveUser();
                    u.id = rs.getInt("id");
                    u.username = rs.getString("username");
                    u.email = rs.getString("email");
                    u.aboutMe = rs.getString("about_me");
                    u.role = rs.getString("role");
                    u.creationDate = rs.getLong("creation_date");
                    return u;
                }
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return null;
    }

    public static String updateUserProfile(int id, String username, String email, String plainPassword, String aboutMe) {
        if (username == null || username.isBlank()) return "Error: Username cannot be empty.";

        // Sanitize
        username = username.trim();
        if (email != null) email = email.trim();
        if (aboutMe != null) aboutMe = aboutMe.trim();

        try (Connection conn = getConnection()) {

            // Single statement executes are auto-committed safely by JDBC
            if (plainPassword != null && !plainPassword.isBlank()) {
                if (plainPassword.length() < 6) return "Error: Password must be at least 6 characters.";
                String hash = org.mindrot.jbcrypt.BCrypt.hashpw(plainPassword, org.mindrot.jbcrypt.BCrypt.gensalt(12));

                String sql = "UPDATE users SET username = ?, email = ?, password_hash = ?, about_me = ? WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, username);
                    if (email == null || email.isEmpty()) ps.setNull(2, java.sql.Types.VARCHAR); else ps.setString(2, email);
                    ps.setString(3, hash);
                    ps.setString(4, aboutMe);
                    ps.setInt(5, id);
                    ps.executeUpdate();
                }
            } else {
                String sql = "UPDATE users SET username = ?, email = ?, about_me = ? WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, username);
                    if (email == null || email.isEmpty()) ps.setNull(2, java.sql.Types.VARCHAR); else ps.setString(2, email);
                    ps.setString(3, aboutMe);
                    ps.setInt(4, id);
                    ps.executeUpdate();
                }
            }
            return "Success";
        } catch (SQLException e) {
            if (e.getMessage().toLowerCase().contains("unique")) {
                return "Error: Username or Email is already taken.";
            }
            e.printStackTrace();
            return "Error: Database failure while updating profile.";
        }
    }

    public static void deleteUser(int id) {
        try (Connection conn = getConnection()) {
            // Attempt to completely erase the user
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM users WHERE id = ?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            // If deletion is blocked because they created invite keys, anonymize them instead!
            if (e.getMessage().toLowerCase().contains("constraint") || e.getMessage().toLowerCase().contains("foreign key")) {
                try (Connection conn = getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                             "UPDATE users SET username = ?, email = NULL, password_hash = '', about_me = '', banned = 1 WHERE id = ?")) {
                    ps.setString(1, "[Deleted User " + id + "]");
                    ps.setInt(2, id);
                    ps.executeUpdate();
                } catch (SQLException ex) { throw new RuntimeException(ex); }
            } else {
                throw new RuntimeException(e);
            }
        }
    }

    // Add to DatabaseHandler.java

    /**
     * Calculates the Hamming Distance between two longs.
     * Lower value = Higher similarity. 0 is an exact match.
     */
    public static int getHammingDistance(long h1, long h2) {
        return Long.bitCount(h1 ^ h2);
    }

    /**
     * Fetches all media records with their hashes for in-memory comparison.
     */
    public static List<TwitterMedia> getAllMediaWithHashes() {
        List<TwitterMedia> mediaList = new ArrayList<>();
        String sql = "SELECT id, post_id, local_path, perceptual_hash, data_hash, content_rating, safety_rating FROM media";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                TwitterMedia m = new TwitterMedia();
                m.id = rs.getInt("id");
                m.postId = rs.getString("post_id");
                m.localPath = rs.getString("local_path");
                m.perceptualHash = rs.getString("perceptual_hash");
                m.dataHash = rs.getString("data_hash");
                m.contentRating = rs.getString("content_rating");
                m.safetyRating = rs.getString("safety_rating");
                mediaList.add(m);
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return mediaList;
    }

    // =========================================================
    // STATIC / SINGLE-TOKEN SESSION MANAGEMENT
    // =========================================================

    /**
     * Retrieves an existing valid user token, or generates a new one if none exists.
     * This ensures only ONE user token exists in the sessions table per user.
     */
    public static String getOrCreateUserToken(int userId, long expiresAt) {
        String selectSql = "SELECT session_token FROM sessions WHERE user_id = ? AND token_type = 'User' LIMIT 1";

        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("session_token"); // Return existing token
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        // If no token exists, generate a new one and write it synchronously to return it safely
        String newToken = "user_" + generateSecureTokenString();

        // Write it synchronously so the login workflow can immediately hand it back to the client
        try (Connection conn = getConnection()) {
            String insertSql = "INSERT INTO sessions (user_id, session_token, token_type, created_at, expires_at) VALUES (?, ?, 'User', ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setInt(1, userId);
                ps.setString(2, newToken);
                ps.setLong(3, Instant.now().getEpochSecond());
                if (expiresAt == -1) {
                    ps.setNull(4, java.sql.Types.INTEGER);
                } else {
                    ps.setLong(4, expiresAt);
                }
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return newToken;
    }

    /**
     * Helper to generate a secure random string token.
     */
    private static String generateSecureTokenString() {
        byte[] randomBytes = new byte[32];
        RANDOM.nextBytes(randomBytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /**
     * Looks up an active session or bot token in the database.
     * Returns an ArchiveUser object if valid, otherwise returns null.
     */
    public static ArchiveUser getSessionByToken(String token) {
        String sql = "SELECT u.id, u.username, u.email, u.password_hash, u.role, " +
                "u.restriction_level, u.banned, u.invite_key_used, u.note, " +
                "u.about_me, u.creation_date, s.expires_at " +
                "FROM sessions s " +
                "JOIN users u ON s.user_id = u.id " +
                "WHERE s.session_token = ?";

        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    boolean banned = rs.getBoolean("banned");
                    if (banned) {
                        return null; // Banned users cannot authenticate
                    }

                    long expiresAt = rs.getLong("expires_at");
                    // rs.wasNull() handles cases where database value is NULL (never expires, i.e., bots)
                    if (!rs.wasNull() && java.time.Instant.now().getEpochSecond() > expiresAt) {
                        return null; // Expired
                    }

                    // Construct and return ArchiveUser
                    ArchiveUser user = new ArchiveUser();
                    user.id = rs.getInt("id");
                    user.username = rs.getString("username");
                    user.email = rs.getString("email");
                    user.passwordHash = rs.getString("password_hash");
                    user.role = rs.getString("role");
                    user.restrictionLevel = rs.getString("restriction_level");
                    user.banned = banned;
                    user.inviteKeyUsed = rs.getString("invite_key_used");
                    user.note = rs.getString("note");
                    user.aboutMe = rs.getString("about_me");
                    user.creationDate = rs.getLong("creation_date");

                    return user;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    /**
     * Deletes a user's current token. This acts as a standard logout.
     */
    public static void deleteSession(String token) {
        writeQueue.runAsyncWrite(conn -> {
            String sql = "DELETE FROM sessions WHERE session_token = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, token);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Completely revokes all tokens (User and Bot tokens) for a user ID.
     */
    public static void deleteSessionsByUserId(int userId) {
        writeQueue.runAsyncWrite(conn -> {
            String sql = "DELETE FROM sessions WHERE user_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, userId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Deletes expired session entries.
     */
    public static void cleanExpiredSessions() {
        writeQueue.runAsyncWrite(conn -> {
            String sql = "DELETE FROM sessions WHERE expires_at IS NOT NULL AND expires_at < ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, Instant.now().getEpochSecond());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    // ==========================================
    // BOT TOKEN MANAGEMENT
    // ==========================================

    /**
     * Generates a new Bot token for the user, revoking any existing ones so they only ever have one.
     */
    public static String generateBotToken(int userId) {
        String token = "bot_" + generateSecureTokenString();

        writeQueue.runAsyncWrite(conn -> {
            // First, delete any existing bot tokens for this user
            String deleteSql = "DELETE FROM sessions WHERE user_id = ? AND token_type = 'Bot'";
            try (PreparedStatement ps = conn.prepareStatement(deleteSql)) {
                ps.setInt(1, userId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

            // Then insert the new one
            String insertSql = "INSERT INTO sessions (user_id, session_token, token_type, created_at, expires_at) VALUES (?, ?, 'Bot', ?, NULL)";
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setInt(1, userId);
                ps.setString(2, token);
                ps.setLong(3, Instant.now().getEpochSecond());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });

        return token;
    }

    /**
     * Retrieves the single active Bot token belonging to a specific user.
     * Returns null if they do not have one.
     */
    public static String getBotTokenByUserId(int userId) {
        String sql = "SELECT session_token FROM sessions WHERE user_id = ? AND token_type = 'Bot' LIMIT 1";

        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("session_token");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    /**
     * Revokes (deletes) the bot token for a user.
     */
    public static void revokeBotToken(int userId) {
        writeQueue.runAsyncWrite(conn -> {
            String sql = "DELETE FROM sessions WHERE user_id = ? AND token_type = 'Bot'";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, userId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
