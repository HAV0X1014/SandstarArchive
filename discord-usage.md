# How to use the Discord frontend
A basic guide about how to use Discord to control Sandstar Archive. A general outline of usage on Discord.

## Bot
Create a bot at https://discord.com/developers/home and put the token in "BotToken". Add it to the server we are about to create, or one you already have.

## Server setup
Create a server, and copy its ID. Place the server ID in "ServerID".

This is an example channel setup for an archive with the safety ratings Safe, Questionable, and NSFW. The #chat and #commands channels are for users to put commands into (not a strict requirement). The channel IDs of #safe, #questionable, and #nsfw should be parallel to the Safe, Questionable, and NSFW safety ratings in the config, and the channel ID of #artists should be set under "AccountsChannel".

```
Text Channels
├── #chat
└── #commands

Feed Channels
├── #safe
├── #questionable
└── #nsfw (By the way you can't forward messages from channels with "nsfw" in them)

Artists
└── #artists
```

Additionally, you will need to create one role to be your "allowed users" role. As of now, the role gives permission roughly as powerful as Execute permission on the website, however there are missing features from Discord. Put the role ID of this role in "AllowedUsersRoleID".

"RejectedChannel" is currently not used, however it may be used in the future for a classifier model to sort posts into safety rating channels instead of the artist's rating.

## Slash commands
Description of slash commands and the arguments they take. If the argument is self explanitory, it might not be described.

### /accountinfo screenname
Retrieves account info from the database for the account by screen name. Will return null values if the account does not exist in the database.

### /addaccount screenname artist downloadstatus accountsafetyrating
Track a new account and create or link it to an artist. Requires filling out information about the account and artist.
- screenname
  - The @name of the account on twitter.
- artist
  - The name of the artist to create or link to. IMPORTANT: if you are adding a new account to an ___existing artist___, you need to put in the ___existing artist___'s name here. For example, if you already have an artist "Kaby" under the screen name @ncb123 and you want to add their other account @my_alt_heavy, you would fill artist as "Kaby" exactly. The response will tell you that the account is linked to the existing artist.
- downloadstatus
  - Whether to check the account for new posts when scrape sessions occur. True is yes, False is no.
- accountsafetyrating
  - One of the defined safety ratings to put the account under. In the above example, the options would be Safe, Questionable, or NSFW.

### /addalias artistname aliasname safetyrating
Add an alias to an existing artist. Meant to assist searching for artists or have multiple names for one artist while retaining one "true" name.
- artistname
  - The name of the artist to create the alias for.
- aliasname
  - The name of the alias to create for the artist. Usually this would be something like an old name or shortened version. For example, if "Kaby" used to go by "Rascal13" you could add "Rascal13" as an alias.
- safetyrating
  - The safety rating of the alias. Useful if the artist has a name for one kind of content, and one name for another.

### /deleteaccount screenname
Delete account from database. Deletes account while keeping posts, media, etc.

### /downloadpost url contentrating postsafetyrating
Download a single post. Makes new account & artist entry if not already present. Newly created accounts will have their safety rating set to null (none), download status set to false, and pull all data from twitter. Not recommended to create accounts through this command.
- url
  - The URL of the post from x.com/twitter.com/fxtwitter.com/anywhere that is on twitter. It strips any leading or trailing text after /status/ and takes only the post ID. Also takes pure post IDs from twitter.
- contentrating
  - The content rating of the post.
- postsafetyrating
  - The safety rating of the post.

### /editaccount screenname [optional: displayname accountstatus isprotected downloadstatus lastscrapedid safetyrating]
Set twitter account info to the desired values. Use when the account changes on twitter.
- screenname
  - The screen name of the account to edit. This is the required lookup parameter, and you cannot change it using this command.
Most of these are self explanitory, except accountstatus, lastscrapedid and downloadstatus
- accountstatus
  - The status of the account on twitter. Must be Active, Deleted, or Suspended. This should match the actual account's standing on twitter.
- lastscrapedid
  - The last ID that has been scraped for this account. Used to set stopping point for scrape sessions. Set this to the last successfully scraped post ID, should there be an error where posts are not scraped properly.
- downloadstatus
  - True/False of whether to download the account's posts in scrape sessions.

### /gettwitteraccountinfo screenname
Look up account info by twitter @name. Helpful to see if your account can see/scrape an account successfully. Scrapes twitter for account info, and does not use the database.

### /ping
Simple check if the bot is running, and start time.

### /scrapefrom postid [optional: stopid]
Scrape an account continuing from the specified post ID. Also (attempts) to create account if they do not exist. Not recommended to create accounts through this command.
- postid
  - The post ID to start a scrape from. Looks up account info automatically, requires only numeric post ID.
- stopid
  - The post ID to stop the scrape at. Useful if old posts are already downloaded and you want to stop before them.

### /setartistdescription artistname description
Set description for an artist by name.

## Rating Buttons
There is a slight debounce to collect edits to post ratings. The timer is started after the first button is clicked, and is submitted to the database and messages after 2 seconds. This means that you could click Safe, NSFW, NonKF, KF in quick succession, buit only the last safety and/or content rating will be submitted.

If it seems that your edits arent going through, check if the message is more than 1 hour old. Discord rate limits edits to messages within threads to 1 edit every 2-3 seconds after they have been up for 1 hour. Your edits will still go through, however they may be delayed by the queue of the rate limiter.
