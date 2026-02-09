package katworks.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import java.util.ArrayList;

import static katworks.Main.config;

public class DiscordMain {
    public static JDA jda;
    public static String self;
    public static TextChannel feedChannel;
    public static TextChannel statusChannel;
    public static Role allowedRole;

    public void start() {
        try {
            jda = JDABuilder.createDefault(config.botToken)
                .setActivity(Activity.customStatus("SandstArchive online!"))
                .addEventListeners(new ButtonInteractionHandler())
                .addEventListeners(new SlashCommandHandler())
                .build();
            jda.awaitReady();
            registerCommands();

            self = jda.getSelfUser().getId();
            System.out.println("Logged in as " + jda.getSelfUser().getName());
            statusChannel = jda.getTextChannelById(config.statusChannel);
            SendStatusMessage.sendMessage("Online.");
            feedChannel = jda.getTextChannelById(config.rawFeedChannel);
            allowedRole = jda.getRoleById(config.allowedUsersRoleID);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void registerCommands() {
        System.out.println("Registering commands...");
        ArrayList<CommandData> cd = new ArrayList<>();

        OptionData postContentOptions = new OptionData(OptionType.STRING,"contentrating","The content rating of the post.",true);
        for (String contentRating : config.contentRatings) {
            if (!contentRating.equals("Waiting")) {
                postContentOptions.addChoice(contentRating,contentRating);
            }
        }
        OptionData accountStatusOptions = new OptionData(OptionType.STRING,"accountstatus","The status of the account. Must be Active, Deleted, or Suspended.",false)
                .addChoice("Active","Active")
                .addChoice("Deleted","Deleted")
                .addChoice("Suspended","Suspended");
        OptionData editAccountSafetyOptions = new OptionData(OptionType.STRING,"safetyrating","The safety rating of the account.",false);
        OptionData accountSafetyOptions = new OptionData(OptionType.STRING,"accountsafetyrating","The safety rating of the account.",true);
        OptionData postSafetyOptions = new OptionData(OptionType.STRING,"postsafetyrating","The safety rating of the post.",true);
        for (String safetyRating : config.safetyRatings) {
            if (!safetyRating.equals("Waiting")) {
                editAccountSafetyOptions.addChoice(safetyRating,safetyRating);
                accountSafetyOptions.addChoice(safetyRating,safetyRating);
                postSafetyOptions.addChoice(safetyRating,safetyRating);
            }
        }
        cd.add(Commands.slash("addaccount","Track a new account. Requires filling out (some) artist details too.")
                .addOption(OptionType.STRING,"screenname","The @name of the account.",true)
                .addOption(OptionType.STRING,"artist","The name of the artist - separate from handle.",true)
                .addOption(OptionType.BOOLEAN,"downloadstatus","Download status of the account.",true)
                .addOptions(accountSafetyOptions));

        cd.add(Commands.slash("accountinfo","Retrieve account info from the database.")
                .addOption(OptionType.STRING,"screenname","The @name of the account."));

        cd.add(Commands.slash("downloadpost","Download a single post. Makes new account & artist entry if not already present.")
                .addOption(OptionType.STRING,"url","The URL of the tweet to download.",true)
                .addOptions(postContentOptions)
                .addOptions(postSafetyOptions)
                /*.addOption(OptionType.STRING,"artistname","The name of the artist if they do not exist yet.",false)*/);

        cd.add(Commands.slash("gettwitteraccountinfo","Look up account info by twitter @name.")
                .addOption(OptionType.STRING,"screenname","The @name of the account.",true));

        cd.add(Commands.slash("editaccount","Set twitter account info to the desired values.")
                .addOption(OptionType.STRING,"screenname","The @name of the account.",true)
                .addOption(OptionType.STRING,"displayname","The displayed name of the account.",false)
                .addOptions(accountStatusOptions) //Active, Deleted, or Suspended
                .addOption(OptionType.BOOLEAN,"isprotected","True/False if the account is protected.",false)
                .addOption(OptionType.BOOLEAN,"downloadstatus","True/False of whether to download the account.",false)
                .addOptions(editAccountSafetyOptions)); //just the safety ratings

        cd.add(Commands.slash("deleteaccount","Delete account from database.")
                .addOption(OptionType.STRING,"screenname","The @name of the account.",true));

        //todo: make a "link account to artist" command
        jda.updateCommands().addCommands(cd).queue();
    }
}
