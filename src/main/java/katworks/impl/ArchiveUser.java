package katworks.impl;

public class ArchiveUser {
    public int id;
    public String username;
    public String email;    //not required
    public String passwordHash;
    public String role; //possible values are Read, Write, and Execute
    public String restrictionLevel; //unused, but default is None
    public boolean banned;
    public String inviteKeyUsed;
    public String note;
    public String aboutMe;
    public long creationDate;
}
