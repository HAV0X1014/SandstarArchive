package katworks.impl;

public class InviteKey {
    public int id;
    public String inviteKey;
    public String grantRole; //default is "Read". Possible values are Read, Write, Execute
    public int maxUses; //-1 is unlimited, 0 is disabled.
    public int timesUsed; //increment on each signup
    public long expiresAt; //-1 for never, else its a unix timestamp
    public int createdByUserId;
    public long creationDate; //unix timestamp

    @Override
    public String toString() {
        return "InviteKey{" +
                "id=" + id +
                ", inviteKey='" + inviteKey + '\'' +
                ", grantRole='" + grantRole + '\'' +
                ", maxUses=" + maxUses +
                ", timesUsed=" + timesUsed +
                ", expiresAt=" + expiresAt +
                ", createdByUserId=" + createdByUserId +
                ", creationDate=" + creationDate +
                '}';
    }
}
