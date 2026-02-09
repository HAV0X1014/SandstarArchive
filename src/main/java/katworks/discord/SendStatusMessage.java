package katworks.discord;

import static katworks.discord.DiscordMain.statusChannel;

public class SendStatusMessage {
    public static void sendMessage(String content) {
        statusChannel.sendMessage(content.length() > 1999 ? content.substring(0,2000) : content).queue();
    }
}
