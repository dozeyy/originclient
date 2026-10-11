# Builds the Origin Client Discord server: roles (colours, permissions, order),
# categories + channels (types, topics, privacy, user limits, forum tags),
# Community + rules/welcome posts, AFK channel, and Onboarding role questions.
#
# Run on Will's PC (Windows PowerShell 5.1 or PowerShell 7):
#   powershell -ExecutionPolicy Bypass -File tools\discord\build-server.ps1
#
# Needs a bot token from the Origin Client app (discord.com/developers ->
# Bot -> Reset Token), and that bot added to the server with Administrator.
# The token is typed in hidden and only lives in this PowerShell process.
#
# Safe to re-run: any role or channel whose name already exists is skipped,
# so it fills gaps in a server that's half set up instead of duplicating.
# Bots (TempVoice for Join-to-Create voice, Carl-bot) can't be added by any
# script -- Discord requires a person to click "Add to server" for each.

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$secure = Read-Host 'Paste the bot token (hidden)' -AsSecureString
$token = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
$headers = @{ Authorization = "Bot $token"; 'User-Agent' = 'DiscordBot (https://originclient.org, 1.0)' }

# One request with Discord's rate limits honoured: on 429, wait the
# retry_after Discord sends and try again. Bodies go out as UTF-8 bytes so
# emoji in names survive Windows PowerShell 5.1's default encoding.
function Api([string]$method, [string]$path, $body = $null) {
    $uri = "https://discord.com/api/v10$path"
    while ($true) {
        try {
            if ($null -ne $body) {
                $json = ConvertTo-Json -InputObject $body -Depth 10 -Compress
                $bytes = [Text.Encoding]::UTF8.GetBytes($json)
                return Invoke-RestMethod -Method $method -Uri $uri -Headers $headers -Body $bytes -ContentType 'application/json; charset=utf-8'
            }
            return Invoke-RestMethod -Method $method -Uri $uri -Headers $headers
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

# ---- Permission bits (Discord's numbering) ----
$P = @{
    ADMIN = [UInt64]8; KICK = [UInt64]2; BAN = [UInt64]4; ADD_REACTIONS = [UInt64]64
    AUDIT = [UInt64]128; VIEW = [UInt64]1024; SEND = [UInt64]2048; MANAGE_MSG = [UInt64]8192
    MENTION_ALL = [UInt64]131072; CONNECT = [UInt64]1048576; SPEAK = [UInt64]2097152
    MUTE = [UInt64]4194304; DEAFEN = [UInt64]8388608; MOVE = [UInt64]16777216
    NICKS = [UInt64]134217728; MANAGE_THREADS = [UInt64]17179869184
    PUBLIC_THREADS = [UInt64]34359738368; PRIVATE_THREADS = [UInt64]68719476736
    THREAD_SEND = [UInt64]274877906944; TIMEOUT = [UInt64]1099511627776
}
function Bits([string[]]$names) { $v = [UInt64]0; foreach ($n in $names) { $v = $v -bor $P[$n] }; return "$v" }
function Hex([string]$h) { return [Convert]::ToInt32($h.TrimStart('#'), 16) }

# ---- Pick the server ----
$guilds = @(Api GET '/users/@me/guilds')
if ($guilds.Count -eq 0) { throw 'The bot is not in any server yet. Add it with the OAuth2 link first.' }
if ($guilds.Count -eq 1) { $guild = $guilds[0] } else {
    for ($i = 0; $i -lt $guilds.Count; $i++) { Write-Host "  [$i] $($guilds[$i].name)" }
    $guild = $guilds[[int](Read-Host 'Which server number')]
}
$G = $guild.id
Write-Host "Building into: $($guild.name)" -ForegroundColor Cyan

# ---- Roles: listed TOP to BOTTOM, the order they'll end up in ----
# Staff/special first, then the self-pick groups, Member and Muted last.
# Self-pick roles are not hoisted so the member list stays tidy.
$roleSpec = @(
    @{ n = 'Founder';         c = '#F1C40F'; h = $true;  p = @('ADMIN') }
    @{ n = 'Developer';       c = '#9B59FF'; h = $true;  p = @('MANAGE_MSG','MANAGE_THREADS','MENTION_ALL','AUDIT') }
    @{ n = 'Moderator';       c = '#E74C3C'; h = $true;  p = @('KICK','BAN','TIMEOUT','MANAGE_MSG','MANAGE_THREADS','AUDIT','MUTE','DEAFEN','MOVE','NICKS') }
    @{ n = 'Support';         c = '#E67E22'; h = $true;  p = @('MANAGE_MSG','MANAGE_THREADS','TIMEOUT') }
    @{ n = 'Partner';         c = '#4ED6A7'; h = $true;  p = @() }
    @{ n = 'Content Creator'; c = '#FF4FA3'; h = $true;  p = @() }
    @{ n = 'Tester';          c = '#3BA9FF'; h = $true;  p = @() }
    # colour roles
    @{ n = "🔴 Crimson";   c = '#DC143C' }; @{ n = "🟠 Sunset";  c = '#FF7F50' }
    @{ n = "🟡 Lemon";     c = '#FFF44F' }; @{ n = "🟢 Mint";    c = '#98FF98' }
    @{ n = "🔵 Ocean";     c = '#1E90FF' }; @{ n = "🟣 Lavender"; c = '#B57EDC' }
    @{ n = "⚪ Snow";      c = '#FFFFFF' }; @{ n = "⚫ Shadow";  c = '#2C2C2C' }
    # how do you play
    @{ n = "⚔️ PvP";        c = '#FF3B3B' }; @{ n = "🛏️ Bedwars";  c = '#FF8A3D' }
    @{ n = "🌲 Survival";   c = '#2ECC71' }; @{ n = "🏗️ Builder";  c = '#FFD54F' }
    @{ n = "🧱 Skyblock";   c = '#5DADE2' }; @{ n = "🛠️ Modder / Dev"; c = '#B388FF' }
    # region
    @{ n = "🌎 NA";   c = '#1E88E5' }; @{ n = "🌍 EU";  c = '#3949AB' }
    @{ n = "🌏 Asia"; c = '#D81B60' }; @{ n = "🌏 OCE"; c = '#00ACC1' }
    @{ n = "🌎 SA";   c = '#7CB342' }
    # main version
    @{ n = '1.21.x'; c = '#29B6F6' }; @{ n = '1.20.x'; c = '#9CCC65' }; @{ n = '1.16–1.19'; c = '#FFA726' }
    # platform
    @{ n = "💻 PC"; c = '#90A4AE' }; @{ n = "🍎 Mac"; c = '#CFD8DC' }; @{ n = "🐧 Linux"; c = '#FFB300' }
    # pings
    @{ n = "📢 Announcements"; c = '#FF7043' }; @{ n = "🆕 Updates";  c = '#26C6DA' }
    @{ n = "🎉 Events";        c = '#AB47BC' }; @{ n = "🎁 Giveaways"; c = '#EC407A' }
    @{ n = "🧪 Beta Testing";  c = '#66BB6A' }
    @{ n = 'Member'; c = '#E0E0E0' }
    @{ n = 'Muted';  c = '#4A4A4A' }
)

$existingRoles = @(Api GET "/guilds/$G/roles")
$R = @{}
foreach ($r in $existingRoles) { $R[$r.name] = $r.id }
$everyone = ($existingRoles | Where-Object { $_.id -eq $G }).id

foreach ($s in $roleSpec) {
    if ($R.ContainsKey($s.n)) { Write-Host "  role exists: $($s.n)"; continue }
    $perm = if ($s.p) { Bits $s.p } else { '0' }
    $made = Api POST "/guilds/$G/roles" @{ name = $s.n; color = (Hex $s.c); hoist = [bool]$s.h; mentionable = $false; permissions = $perm }
    $R[$s.n] = $made.id
    Write-Host "  + role $($s.n)"
}

# Order: the bot's own role must stay highest, so ours stack directly under it.
$me = Api GET '/users/@me'
$member = Api GET "/guilds/$G/members/$($me.id)"
$allRoles = @(Api GET "/guilds/$G/roles")
$botTop = ($allRoles | Where-Object { $member.roles -contains $_.id } | Measure-Object -Property position -Maximum).Maximum
$pos = [int]$botTop - 1
$moves = New-Object System.Collections.ArrayList
foreach ($s in $roleSpec) {
    if ($pos -lt 1) { break }
    [void]$moves.Add(@{ id = $R[$s.n]; position = $pos }); $pos--
}
try { [void](Api PATCH "/guilds/$G/roles" $moves.ToArray()); Write-Host '  roles ordered' }
catch { Write-Host "  (couldn't reorder roles: drag the Origin Client bot role to the very top, then re-run) $_" -ForegroundColor Yellow }

# ---- Channels ----
$existingCh = @(Api GET "/guilds/$G/channels")
function FindCh([string]$name, $parent) {
    return ($existingCh | Where-Object { $_.name -eq $name -and ($null -eq $parent -or $_.parent_id -eq $parent) } | Select-Object -First 1)
}
function Ow([string]$roleId, [string[]]$allow = @(), [string[]]$deny = @()) {
    return @{ id = $roleId; type = 0; allow = (Bits $allow); deny = (Bits $deny) }
}
$mutedOw = Ow $R['Muted'] @() @('SEND','ADD_REACTIONS','SPEAK','THREAD_SEND','PUBLIC_THREADS','PRIVATE_THREADS')
# Each category's overwrites, so its channels are created with the same privacy
# spelled out (not relying on Discord to copy them from the parent).
$catOws = @{}

function Category([string]$name, $overwrites = @()) {
    $hit = FindCh $name $null
    $ows = @($overwrites) + @($mutedOw)
    if ($hit -and $hit.type -eq 4) { $catOws[$hit.id] = $ows; Write-Host "  category exists: $name"; return $hit.id }
    $made = Api POST "/guilds/$G/channels" @{ name = $name; type = 4; permission_overwrites = $ows }
    $catOws[$made.id] = $ows
    Write-Host "+ $name"; return $made.id
}
# type: 0 text, 2 voice, 5 announcement, 13 stage, 15 forum
function Chan([string]$parent, [string]$name, [int]$type = 0, [string]$topic = '', [int]$limit = 0, $tags = $null, $overwrites = $null) {
    $hit = FindCh $name $parent
    if ($hit) { Write-Host "    exists: $name"; return $hit.id }
    $b = @{ name = $name; type = $type; parent_id = $parent }
    if ($topic -and $type -ne 2 -and $type -ne 13) { $b.topic = $topic }
    if ($limit -gt 0) { $b.user_limit = $limit }
    if ($type -eq 2 -or $type -eq 13) { $b.bitrate = 64000 }
    if ($tags) { $b.available_tags = @($tags | ForEach-Object { @{ name = $_ } }) }
    if ($null -ne $overwrites) { $b.permission_overwrites = @($overwrites) + @($mutedOw) }
    elseif ($catOws.ContainsKey($parent)) { $b.permission_overwrites = $catOws[$parent] }
    $made = Api POST "/guilds/$G/channels" $b
    Write-Host "    + $name"; return $made.id
}

$readOnly = @(Ow $everyone @() @('SEND','PUBLIC_THREADS','PRIVATE_THREADS'))
$postersOnly = $readOnly + @((Ow $R['Founder'] @('SEND')), (Ow $R['Developer'] @('SEND')))
function PrivateOws([string[]]$roleNames) {
    $o = @(Ow $everyone @() @('VIEW'))
    foreach ($n in $roleNames) { $o += Ow $R[$n] @('VIEW') }
    return $o
}

# Community needs a rules channel and a staff updates channel first, plus
# Medium verification and the explicit-media filter on for everyone.
$info = Category 'INFO' $readOnly
$welcome = Chan $info 'welcome' 0 'What Origin is and where to get it.'
$rules   = Chan $info 'rules' 0 'Read these before chatting.'
$staffCat = Category 'STAFF' (PrivateOws @('Founder','Developer','Moderator','Support'))
$modlog  = Chan $staffCat 'mod-log' 0 'AutoMod alerts and Discord staff updates.'

try {
    [void](Api PATCH "/guilds/$G" @{ verification_level = 2; explicit_content_filter = 2 })
    [void](Api PATCH "/guilds/$G" @{ features = @('COMMUNITY'); rules_channel_id = $rules; public_updates_channel_id = $modlog })
    Write-Host '  Community enabled'
} catch { Write-Host "  (Community not enabled: $_ -- announcement/forum/stage channels will be created as plain text/voice)" -ForegroundColor Yellow }
$guildNow = Api GET "/guilds/$G"
$community = @($guildNow.features) -contains 'COMMUNITY'
$ANN = if ($community) { 5 } else { 0 }
$FORUM = if ($community) { 15 } else { 0 }
$STAGE = if ($community) { 13 } else { 2 }

$announce = Chan $info 'announcements' $ANN 'Big Origin news.' 0 $null $postersOnly
[void](Chan $info 'updates' $ANN 'Release notes for every launcher update.' 0 $null $postersOnly)
[void](Chan $info 'download' 0 'Download Origin: https://originclient.org')
[void](Chan $info 'faq' 0 'Common questions and fixes.')
[void](Chan $info 'roles' 0 'Pick your roles in Channels & Roles at the top of the channel list.')

$comm = Category 'COMMUNITY'
$general = Chan $comm 'general' 0 'Main chat.'
$media = Chan $comm 'media' 0 'Screenshots and clips. Origin: F2, then Copy image.'
[void](Chan $comm 'showcase' $FORUM 'Show off your HUD, shaders and builds.' 0 @('HUD','Shaders','Builds','PvP'))
[void](Chan $comm 'suggestions' $FORUM 'Ideas for Origin.' 0 @('Mod idea','Launcher','UI','Accepted','Planned','Denied'))
$ltp = Chan $comm 'looking-to-play' 0 'Find people to play with.'
$offtopic = Chan $comm 'off-topic' 0 'Anything that is not Minecraft.'
[void](Chan $comm 'memes' 0 'Memes.')
$botcmd = Chan $comm 'bot-commands' 0 'Keep bot commands here.'

$modes = Category 'GAMEMODES'
$pvp = Chan $modes 'pvp' 0 'PvP, practice and duels.'
$bw  = Chan $modes 'bedwars' 0 'Bed Wars and Hypixel.'
[void](Chan $modes 'survival' 0 'Survival and SMP.')
[void](Chan $modes 'builds' 0 'Building and creative.')

$sup = Category 'SUPPORT'
$help = Chan $sup 'help' $FORUM 'One post per problem.' 0 @('Launcher','Crash','Shaders','Mods','Install','Solved')
[void](Chan $sup 'bug-reports' $FORUM 'Version / What happened / Steps / Screenshot or log.' 0 @('Launcher','Mod','Crash','Visual','Fixed'))
[void](Chan $sup 'crash-logs' 0 'Paste crash reports here.')

$test = Category 'TESTING' (PrivateOws @('Founder','Developer','Moderator','Tester',"🧪 Beta Testing"))
$testPost = @(
    (Ow $everyone @() @('VIEW')),
    (Ow $R['Founder'] @('VIEW','SEND')), (Ow $R['Developer'] @('VIEW','SEND')), (Ow $R['Moderator'] @('VIEW','SEND')),
    (Ow $R['Tester'] @('VIEW') @('SEND')), (Ow $R["🧪 Beta Testing"] @('VIEW') @('SEND')))
[void](Chan $test 'test-builds' 0 'Early builds to try. Developers post here.' 0 $null $testPost)
[void](Chan $test 'tester-feedback' 0 'What worked and what broke.')

$ev = Category 'EVENTS'
[void](Chan $ev 'events' $ANN 'Tournaments and community nights.' 0 $null $postersOnly)
[void](Chan $ev 'giveaways' 0 'Giveaways.')
[void](Chan $ev 'Event Stage' $STAGE)
foreach ($i in 1..4) { [void](Chan $ev "Tournament Team $i" 2 '' 4) }

$voice = Category 'VOICE'
[void](Chan $voice 'General 1' 2); [void](Chan $voice 'General 2' 2)
[void](Chan $voice 'Music & Chill' 2)
[void](Chan $voice 'Stream' $STAGE)
$afk = Chan $voice 'AFK' 2

$games = Category 'GAMES'
[void](Chan $games 'PvP 1' 2 '' 2); [void](Chan $games 'PvP 2' 2 '' 2)
[void](Chan $games 'Bedwars Duos' 2 '' 2); [void](Chan $games 'Bedwars 3s' 2 '' 3); [void](Chan $games 'Bedwars 4s' 2 '' 4)
[void](Chan $games 'Survival / SMP' 2); [void](Chan $games 'Building' 2); [void](Chan $games 'Skyblock' 2)

[void](Chan $staffCat 'staff-chat' 0 'Staff only.')
[void](Chan $staffCat 'reports' 0 'Player reports.')
[void](Chan $staffCat 'Staff' 2)
[void](Chan $staffCat 'Interviews' 2)

try { [void](Api PATCH "/guilds/$G" @{ afk_channel_id = $afk; afk_timeout = 300; system_channel_id = $general }); Write-Host '  AFK + join messages set' } catch { Write-Host "  (AFK not set: $_)" -ForegroundColor Yellow }

# ---- Rules + welcome posts (only into empty channels, so re-runs don't repeat them) ----
function PostOnce([string]$ch, [string]$text) {
    $last = @(Api GET "/channels/$ch/messages?limit=1")
    if ($last.Count -gt 0) { Write-Host "    (already has messages, skipped post)"; return }
    [void](Api POST "/channels/$ch/messages" @{ content = $text })
}
$rulesText = @"
**Origin Client — Rules**
1. Be respectful. No harassment, hate speech, slurs or discrimination.
2. No spam, flooding, or excessive caps/emojis.
3. No advertising or DM advertising without staff permission.
4. Keep content SFW. No NSFW, gore, or shock content.
5. No cheats, hacked clients, or exploits. Origin is a fair-play client.
6. Use the right channels — bugs go in <#BUGS>, help in <#HELP>.
7. Don't ping staff for support; post in <#HELP> and someone will reply.
8. Don't share personal info (yours or anyone else's).
9. Follow Discord's Terms of Service and Community Guidelines.
10. Staff decisions are final. Questions? Message a Moderator.
"@
$existingCh = @(Api GET "/guilds/$G/channels")
$bugsId = (FindCh 'bug-reports' $sup).id
$rulesText = $rulesText.Replace('<#BUGS>', "<#$bugsId>").Replace('<#HELP>', "<#$help>")
PostOnce $rules $rulesText
$welcomeText = @"
Welcome to **Origin Client** — a clean, fast Minecraft client with shaders, performance mods and a full mod menu on every version from 1.16.5 to 1.21.11.

⬇️ Download: https://originclient.org
📢 News: <#$announce>
🛠️ Need help? <#$help>

Pick your roles in **Channels & Roles** to get pinged for updates.
"@
PostOnce $welcome $welcomeText

# ---- Onboarding: the self-pick role questions ----
# Discord's rule: at least 7 default channels, 5 of them chat-able by everyone.
if ($community) {
    $script:seq = [UInt64]([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - 1420070400000) * [UInt64]4194304
    function NextId { $script:seq = [UInt64]($script:seq + [UInt64]1); return [string]$script:seq }
    function MakePrompt([string]$title, [bool]$single, [string[]]$roleNames) {
        $opts = @()
        foreach ($n in $roleNames) { $opts += @{ id = (NextId); title = $n; role_ids = @($R[$n]); channel_ids = @() } }
        return @{ id = (NextId); type = 0; title = $title; single_select = $single; required = $false; in_onboarding = $true; options = $opts }
    }
    $prompts = @(
        (MakePrompt 'Get pinged for…' $false @("📢 Announcements","🆕 Updates","🎉 Events","🎁 Giveaways","🧪 Beta Testing"))
        (MakePrompt 'How do you play?' $true @("⚔️ PvP","🛏️ Bedwars","🌲 Survival","🏗️ Builder","🧱 Skyblock","🛠️ Modder / Dev"))
        (MakePrompt 'Where are you from?' $true @("🌎 NA","🌍 EU","🌏 Asia","🌏 OCE","🌎 SA"))
        (MakePrompt 'Main version?' $true @('1.21.x','1.20.x','1.16–1.19'))
        (MakePrompt 'Platform?' $false @("💻 PC","🍎 Mac","🐧 Linux"))
        (MakePrompt 'Pick a name colour' $true @("🔴 Crimson","🟠 Sunset","🟡 Lemon","🟢 Mint","🔵 Ocean","🟣 Lavender","⚪ Snow","⚫ Shadow"))
    )
    $defaults = @($welcome, $rules, $announce, $general, $media, $ltp, $offtopic, $botcmd, $pvp, $bw)
    try {
        [void](Api PUT "/guilds/$G/onboarding" @{ prompts = $prompts; default_channel_ids = $defaults; enabled = $true; mode = 0 })
        Write-Host '  Onboarding set (role questions live)'
    } catch { Write-Host "  (Onboarding not set automatically: $_ -- set it in Server Settings > Onboarding)" -ForegroundColor Yellow }
}

$token = $null
Write-Host ''
Write-Host 'Done. Next:' -ForegroundColor Green
Write-Host '  1. Add TempVoice (tempvoice.xyz) and run /setup for Join to Create voice.'
Write-Host '  2. Optional: add Carl-bot for logs + auto-giving the Member role.'
Write-Host '  3. Give yourself the Founder role (Server Settings > Members).'
Write-Host '  4. Reset the bot token in the developer portal now that the build is done.'
