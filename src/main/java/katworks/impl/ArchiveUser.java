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

    @Override
    public String toString() {
        return "ArchiveUser{" +
                "id=" + id +
                ", username='" + username + '\'' +
                ", email='" + email + '\'' +
                ", passwordHash='" + passwordHash + '\'' +
                ", role='" + role + '\'' +
                ", restrictionLevel='" + restrictionLevel + '\'' +
                ", banned=" + banned +
                ", inviteKeyUsed='" + inviteKeyUsed + '\'' +
                ", note='" + note + '\'' +
                ", aboutMe='" + aboutMe + '\'' +
                ", creationDate=" + creationDate +
                '}';
    }
}
