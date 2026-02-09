package katworks.database;

import org.sqlite.SQLiteConfig;

import java.sql.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static katworks.Main.config;

public class WriteQueue {
    private final String dbUrl = "jdbc:sqlite:" + config.databasePath;
    private final ExecutorService writerExecutor = Executors.newSingleThreadExecutor();

    /**
     * Initializes write queue, and sets pragmas to make reading not block writing.
     */
    public WriteQueue() {
        // Essential for allowing reads while these writes are happening
        executeImmediate("PRAGMA journal_mode=WAL;");
        executeImmediate("PRAGMA synchronous=NORMAL;");
    }

    /**
     * Submits writes (update, insert) to the database through an asynchronous write queue.
     * Use like:
     * writeQueue.runAsyncWrite(conn -> {
     *   String sql = "UPDATE table SET x WHERE y = ?";
     *   try (PreparedStatement ps = conn.prepareStatement(sql) {
     *     ps.setString(1,"blah");
     *     ps.executeUpdate();
     *   } catch (SQLException e) {
     *     throw new RuntimeException(e);
     *   }
     * });
     */
    public void runAsyncWrite(Consumer<Connection> task) {
        writerExecutor.submit(() -> {
            try (Connection conn = getConnection()) {
                // We wrap every task in a transaction for safety and speed
                conn.setAutoCommit(false);
                task.accept(conn);
                conn.commit();
            } catch (Exception e) {
                System.err.println("Database write task failed: " + e.getMessage());
            }
        });
    }

    /**
     * Helper to get a connection with the correct timeout
     */
    private Connection getConnection() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(5000); // This is the most important line
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        return DriverManager.getConnection(dbUrl,config.toProperties());
    }

    // For startup tasks like creating tables
    private void executeImmediate(String sql) {
        try (Connection conn = getConnection(); Statement s = conn.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) { e.printStackTrace(); }
    }
}