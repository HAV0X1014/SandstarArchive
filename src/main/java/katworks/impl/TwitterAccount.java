package katworks.impl;

public class TwitterAccount {
    public String twitterId;
    public int artistId;
    public String screenName;
    public String displayName;
    public String accountStatus;
    public boolean isProtected;
    public String lastScrapedId;
    public boolean downloadStatus;
    public String discordThreadId;
    public String safetyRating;

    public TwitterAccount() {

    }

    @Override
    public String toString() {
        return "TwitterAccount{" +
                "twitterId='" + twitterId + '\'' +
                ", artistId=" + artistId +
                ", screenName='" + screenName + '\'' +
                ", displayName='" + displayName + '\'' +
                ", accountStatus='" + accountStatus + '\'' +
                ", isProtected=" + isProtected +
                ", lastScrapedId='" + lastScrapedId + '\'' +
                ", downloadStatus=" + downloadStatus +
                ", discordThreadId='" + discordThreadId + '\'' +
                ", safetyRating='" + safetyRating + '\'' +
                '}';
    }
}
