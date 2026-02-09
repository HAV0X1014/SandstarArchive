# Archive art from Twitter.
This project relies on [c8ff/twitter-scraper-java](https://github.com/c8ff/twitter-scraper-java) to scrape data from Twitter. You should be mindful of what your account is doing and the risks from using a program like this!

# !!!THIS PROJECT IS IN ACTIVE DEVELOPMENT AND THE DATABASE SCHEMA **WILL** CHANGE!!!
Any database schema changes will make the old database incompatible with new versions of the program. I am still editing and refining what I want this archive to be like, so **please** do not store vital data with this yet.

## Usage
1. Compile twitter-scraper-java into a fatjar using shadow, or otherwise fulfil its depenencies (or use the jar provided in `libs/`).
2. Compile this program, or simply run Main.
3. The program will create account.json, and config.json. Fill them out as described inside the json files. Additional instructions for account.json are below.
4. Create or use a Discord bot and server to act as a frontend for the archive.
5. Once you have configured the account and archive how you desire, re-run the program, and it will create your SQLite database and media archive directories.
6. Use the Discord frontend to add, remove, edit artists and accounts, and rate posts.

## How to fill account.json
Go to x.com, inspect element, and go to the 'Network' tab at the top of the inspect element window. Refresh the page, and scroll up to the top of the list of requests that is now frantically filling up the network page. Near the top there should be a `user_flow.json` request. Click this, scroll down to the bottom, and ensure it has `Cookie` on the left and has a long semicolon separated value on the right. I can't give an example, but you'll know it when you see it (it is NOT 'Set-Cookie'.)

Copy the whole value of `Cookie` to the account.json file where it says "cookie". From the same request, copy over the `User-Agent`, `Authorization` and the value  of `X-Csrf-Token` to the account.json file.

## Notes and known issues
- You can have up to 5 Content and Safety ratings.
  - This is due to Discord only allowing 5 buttons per row.
- Discord is not an ideal frontend.
  - I'm trying to make a website frontend and API so other frontends can interact with the database.
- Edit ratelimits in Discord threads are immensely strict after a post has been up for 1 hour.
  - Threads were chosen because a server can have up to 1000 active threads and unlimited archived threads. It is unlikely that anyone will hit these limits.
- "ChannelsForSafetyRatings" in the config is currently not used, and all posts are sent to "RawFeedChannel"
- A proper logging setup should be implemented.
