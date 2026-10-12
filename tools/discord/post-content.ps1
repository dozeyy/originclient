# Fills the Origin Discord's info channels with Origin-styled posts:
# #announcements (what Origin is), a development update, #updates (latest
# release notes), #faq, #download, #roles.
#
# Run on Will's PC (after build-server.ps1 has made the channels):
#   powershell -ExecutionPolicy Bypass -File tools\discord\post-content.ps1
#
# Re-runnable: in each channel it first deletes the posts THIS bot made, then
# posts the current text, so editing the words below and running again
# replaces them instead of doubling up. Messages from people are never touched.
# The words live in the $posts block below -- edit there.

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# ORIGIN_DISCORD_API / ORIGIN_DISCORD_TOKEN only exist for a dry run against a
# local fake Discord; on a normal run neither is set.
$apiBase = if ($env:ORIGIN_DISCORD_API) { $env:ORIGIN_DISCORD_API } else { 'https://discord.com/api/v10' }
if ($env:ORIGIN_DISCORD_TOKEN) { $token = $env:ORIGIN_DISCORD_TOKEN } else {
    $secure = Read-Host 'Paste the bot token (hidden)' -AsSecureString
    $token = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
}
$headers = @{ Authorization = "Bot $token"; 'User-Agent' = 'DiscordBot (https://originclient.org, 1.0)' }

# Same request helper as build-server.ps1: honours rate limits, sends UTF-8
# bytes (emoji survive PowerShell 5.1), and unrolls JSON lists.
function Api([string]$method, [string]$path, $body = $null) {
    $uri = "$apiBase$path"
    while ($true) {
        try {
            if ($null -ne $body) {
                $json = ConvertTo-Json -InputObject $body -Depth 10 -Compress
                $bytes = [Text.Encoding]::UTF8.GetBytes($json)
                $res = Invoke-RestMethod -Method $method -Uri $uri -Headers $headers -Body $bytes -ContentType 'application/json; charset=utf-8'
            } else {
                $res = Invoke-RestMethod -Method $method -Uri $uri -Headers $headers
            }
            return $res
        } catch {
            $msg = $_.ErrorDetails.Message
            if ($msg -and $msg -match '"retry_after"') {
                $wait = [double](($msg | ConvertFrom-Json).retry_after)
                Start-Sleep -Milliseconds ([int]([math]::Ceiling($wait * 1000)) + 250)
                continue
            }
            throw "Discord said no to $method $path : $msg"
        }
    }
}

$guilds = @(Api GET '/users/@me/guilds')
if ($guilds.Count -eq 0) { throw 'The bot is not in any server yet.' }
if ($guilds.Count -eq 1) { $guild = $guilds[0] } else {
    for ($i = 0; $i -lt $guilds.Count; $i++) { Write-Host "  [$i] $($guilds[$i].name)" }
    $guild = $guilds[[int](Read-Host 'Which server number')]
}
$G = $guild.id
$me = Api GET '/users/@me'
$channels = @(Api GET "/guilds/$G/channels")
function ChId([string]$name) {
    $c = $channels | Where-Object { $_.name -eq $name -and $_.type -ne 4 } | Select-Object -First 1
    if ($c) { return $c.id } else { return $null }
}
# <#id> mentions so channel names in the posts are clickable.
function M([string]$name) { $id = ChId $name; if ($id) { return "<#$id>" } else { return "#$name" } }

$ORIGIN = 0xE0E0E0   # the Origin accent: soft white, no hue
$GREEN  = 0x4ED6A7   # the mod menu's mint, for "new" posts
$DL = 'https://github.com/dozeyy/originclient/releases/latest'

function Card([string]$title, [string]$text, [int]$color = $ORIGIN, $fields = $null) {
    $e = @{ title = $title; color = $color }
    if ($text) { $e.description = $text }   # Discord rejects an empty description
    if ($fields) { $e.fields = @($fields | ForEach-Object { @{ name = $_[0]; value = $_[1]; inline = $false } }) }
    return $e
}

# ---------------------------------------------------------------- the words
$posts = [ordered]@{}

$posts['announcements'] = @(
    (Card 'Welcome to Origin Client' @"
Origin is a free Minecraft client built to feel premium on **every** version, from **1.16.5 to 1.21.11**, on Fabric.

**What you get out of the box**
• Shaders that just work (Iris + Sodium), set up for you
• Big FPS boosts with the best performance mods, installed automatically
• A clean Origin look everywhere: title screen, loading screens, menus
• Dozens of built-in mods in one menu (**Right Shift**): FPS, CPS, Keystrokes, Minimap, Zoom, Freelook, Crosshair, Waypoints, Hypixel tools and more
• Updates install themselves; you're always on the latest

⬇️ **Download:** $DL
🛠️ **Help:** $(M 'help')  ·  🐞 **Bugs:** $(M 'bug-reports')  ·  💡 **Ideas:** $(M 'suggestions')
"@ $GREEN)
    (Card 'Development update' @"
**Just shipped**
• Every version now has the same Origin look and mod menu as 1.21.1
• Fixes for zoom, block outlines, waypoint beams, Time Changer and the loading screen
• A smaller installer that always downloads the newest launcher

**In testing now (1.21.1 first)**
• 35 new mods matching Lunar's lineup: Minimap, Direction HUD, Ping, Reach, Combo, Crosshair, Cooldowns, Item Physics, Boss Bar, Titles, Menu Blur, Nick Hider, Auto GG, Quickplay, Discord status, Hypixel + Bed Wars tools, WorldEdit CUI and more
• Video Settings shows every bundled mod as tabs, one click away
• A screenshot pop-up: press F2, then copy the image straight into Discord

**Next up**
• Bringing all the new mods to every version
• Origin-only mods you won't find in other clients
• Newer Minecraft versions

**When do updates come out?**
There are no fixed dates. Each update ships as soon as it's built and tested, and your launcher installs it automatically. Watch $(M 'updates') for every release.
"@)
)

$posts['updates'] = @(
    (Card 'How updates work' @"
• Every release is posted here with what changed.
• The launcher updates itself and the game files for each version. You never need to reinstall.
• Updates are required so everyone is on a working, safe version.
• Testers get early builds in the testing channels. Pick **🧪 Beta Testing** in Channels & Roles.
"@)
    (Card 'Origin Launcher 1.0.42' @"
**Fixes**
• Waypoint beams no longer swing when you turn the camera (1.16.5–1.19.4)
• Zooming out no longer leaves parts of the world missing (1.16.5)
• The loading screen shows ORIGIN instead of empty boxes, on every version
• Scoreboard background colour works on 1.20.4, 1.21 and 1.21.1
• Block outline matches every other version on 1.21.10 and 1.21.11
"@)
)

$posts['faq'] = @(
    (Card 'Frequently asked questions' '' $ORIGIN @(
        @('Is Origin free?', 'Yes. Origin is free to download and use.'),
        @('Which versions are supported?', '1.16.5, 1.17.1, 1.18.2, 1.19.2, 1.19.3, 1.19.4, 1.20, 1.20.1, 1.20.2, 1.20.4, 1.21, 1.21.1, 1.21.4, 1.21.6, 1.21.8, 1.21.10 and 1.21.11. Versions marked "Coming soon" in the launcher are being worked on.'),
        @('How do I open the mod menu?', 'Press **Right Shift** in game. Click any card to turn a mod on, the gear for its settings, and **Edit HUD** to drag things around the screen.'),
        @('How do I turn on shaders?', 'Options → Video Settings → Shader Packs. Drop a shader pack in the folder, then pick it. Iris and Sodium are already installed for you.'),
        @('Is Origin allowed on servers like Hypixel?', 'Origin has no cheats, hacks or unfair advantages. It is a cosmetic and quality-of-life client like other popular clients. Every server has its own rules, so check the server you play on.'),
        @('Can I add my own mods?', 'Not right now. Origin installs a tested set of mods for each version so nothing conflicts and the game always starts. Suggest a mod in ' + (M 'suggestions') + '.'),
        @('Does it work on Mac or Linux?', 'The launcher is Windows only for now.'),
        @('Do I need a Minecraft account?', 'Yes, you sign in with the Microsoft account that owns Minecraft: Java Edition. Your login is stored encrypted on your PC only.'),
        @('My game crashed. What do I do?', 'The launcher shows what caused it and offers to start again with only Origin. If it keeps happening, post the crash in ' + (M 'crash-logs') + ' or open a post in ' + (M 'help') + '.'),
        @('My FPS is low.', 'Turn the shader off or pick a lighter one, lower Render Distance in Video Settings, and close other heavy apps. Origin already installs the main performance mods.'),
        @('How do I share a screenshot?', 'Press **F2**, open chat, click **Copy image** on the pop-up, then paste into Discord.'),
        @('The installer says it can''t download.', 'The installer needs internet to fetch the newest launcher. Check your connection or firewall, then click **Try again**.')
    ))
)

$posts['download'] = @(
    (Card 'Download Origin' @"
**1.** Download **OriginLauncher-Setup.exe** here: $DL
**2.** Run it. It downloads the newest launcher and installs it (internet needed).
**3.** Open Origin, sign in with your Microsoft account, pick a version and press **Play**.

The first launch of each version downloads the game and its mods. After that it starts fast.
Problems? Post in $(M 'help').
"@ $GREEN)
)

$posts['roles'] = @(
    (Card 'Pick your roles' @"
Open **Channels & Roles** at the top of the channel list to choose:

• **Pings:** Announcements, Updates, Events, Giveaways, Beta Testing
• **How you play:** PvP, Bedwars, Survival, Builder, Skyblock, Modder / Dev
• **Region**, **main version** and **platform**
• A **name colour**

**🧪 Beta Testing** unlocks the testing channels with early builds.
"@)
)

# ---------------------------------------------------------------- posting
foreach ($name in $posts.Keys) {
    $ch = ChId $name
    if (-not $ch) { Write-Host "  (no #$name channel, skipped)" -ForegroundColor Yellow; continue }
    # Remove this bot's earlier posts here, so a re-run replaces them.
    $old = @(Api GET "/channels/$ch/messages?limit=50")
    foreach ($m in $old) {
        if ($m.author.id -eq $me.id) { [void](Api DELETE "/channels/$ch/messages/$($m.id)") }
    }
    foreach ($card in $posts[$name]) {
        [void](Api POST "/channels/$ch/messages" @{ embeds = @($card) })
    }
    Write-Host "  posted #$name ($(@($posts[$name]).Count))"
}
$token = $null
Write-Host 'Done.' -ForegroundColor Green
