package katworks.archive;

import katworks.database.DatabaseHandler;
import katworks.discord.SendStatusMessage;
import katworks.impl.TwitterAccount;
import katworks.twitter.TwitterScraper;

import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static katworks.Main.config;

public class ScrapeService {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public ScrapeService() {
    }

    /**
     * Starts the scheduler for the account scrape loop. Interval is pulled from config.
     */
    public void start() {
        scheduler.scheduleWithFixedDelay(this::runGlobalScrape,0, config.checkIntervalHours, TimeUnit.HOURS);
    }

    /**
     * The actual account scrape loop set to run at the interval set in start().
     */
    private void runGlobalScrape() {
        List<TwitterAccount> accountList = DatabaseHandler.getActiveAccounts();
        System.out.println("Scrape initiated at: " + new Date());
        if (config.discordEnabled) SendStatusMessage.sendMessage("Scrape initiated.");
        StringBuilder discordStatusUpdate = new StringBuilder();
        discordStatusUpdate.append("Accounts checked:\n");
        for (TwitterAccount account : accountList) {
            try {
                System.out.println("Checking " + account.screenName + ".");
                TwitterScraper.scrapeByAccount(account);
                discordStatusUpdate.append(account.screenName).append(", ");
            } catch (InterruptedException e) {
                if (config.discordEnabled) SendStatusMessage.sendMessage("Failed to complete scrape for: " + account.screenName + "!");
                System.out.println("Failed to complete scrape for: " + account.screenName + "!");
                throw new RuntimeException(e);
            }
        }
        //if (config.discordEnabled) SendStatusMessage.sendMessage("Scrape cycle complete. Next scrape in " + config.checkIntervalHours + " hours.");
        System.out.println("Scrape cycle complete. Next scrape in " + config.checkIntervalHours + " hours.");
        SendStatusMessage.sendMessage(discordStatusUpdate.toString());
    }
}
