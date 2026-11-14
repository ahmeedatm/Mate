-- Mate pour Cyberpunk 2077 : écrit l'état de la partie dans state.json (~1 s), les blocs lents dans
-- inventory.json, contacts.json et quests.json, et exécute les ordres de commands.json (écrit par le backend de Mate).
-- Tout appel au jeu est sous pcall. Aucun appel inconnu du moteur hors des commandes ci-dessous.
-- Contrat : docs/specs/2026-11-14-plugin-cyberpunk-design.md, section « Contrat ». Pas de variable globale (CET les refuse).
local VERSION = "1.1.0"
local every, acc = 1.0, 0
local seq, tick = 0, 0
local errs = {}
local acks = {}
local pins = {}
local mappinIds = {}
local pinKeys = {} -- pid -> valeur 64 bits de l'identifiant du repère, en chaîne de chiffres (pour pins.json)
local loadErrs = {}
local trackedPin = nil
local lastCmd = nil
local hadPlayer = false
local session = 0
local blocks = { inv = os.time(), contacts = os.time(), quests = os.time() }
local lastJson = {}

local function try(key, f)
  local ok, v = pcall(f)
  if ok then return v end
  errs[key] = tostring(v)
  return nil
end

local function loc(s)
  if s == nil then return "" end
  s = tostring(s)
  if string.find(s, "^LocKey#") then
    local ok, t = pcall(function() return Game.GetLocalizedText(s) end)
    if ok and t and t ~= "" then return t end
  end
  local ok, t = pcall(function() return Game.GetLocalizedTextByKey(s) end)
  if ok and t and t ~= "" then return t end
  return s
end

-- Fils SMS (méthode de dariulone/cyberpunk-vr-port, MIT, prouvée en jeu le 14/11). Le hash Int32 du jeu est relu non signé
-- pour GetEntry, et écrit en chaîne de chiffres (tostring) pour le backend et la tablette.
local function u32(h)
  h = math.floor(tonumber(h) or 0)
  if h < 0 then h = h + 4294967296 end
  return h
end

local function enum(e)
  local ok, n = pcall(function() return tonumber(EnumInt(e)) end)
  return ok and n or -1
end

local function isMulti(c) return enum(c.type) == enum(MessengerContactType.MultiThread) end

-- Messages et choix ouverts d'un fil (téléphone du jeu fermé ; ne marque rien lu).
local function threadData(j, c)
  if isMulti(c) then return j:GetMessagesAndChoices(j:GetEntry(u32(c.conversationHash))) end
  return j:GetFlattenedMessagesAndChoices(j:GetEntry(u32(c.hash)))
end

local function findThread(j, hash)
  local all = MessengerUtils.GetSimpleContactDataArray(j, true, true, true)
  for i = 1, #(all or {}) do
    if tostring(all[i].hash) == tostring(hash) then return all[i] end
  end
  return nil
end

-- Version du fil : nombre de messages et hash du dernier (le backend la renvoie avec le choix, le mod refuse si elle a bougé).
local function threadVersion(j, msgs)
  local n = #(msgs or {})
  local last = n > 0 and tostring(j:GetEntryHash(msgs[n])) or "0"
  return n .. ":" .. last
end

local function itemInfo(pl, id)
  local tdb = ItemID.GetTDBID(id)
  local rec = TweakDBInterface.GetItemRecord(tdb)
  local data = Game.GetTransactionSystem():GetItemData(pl, id)
  local q = nil
  pcall(function() q = tostring(RPGManager.GetItemDataQuality(data)) end)
  local typ = nil
  pcall(function() typ = tostring(rec:ItemType():Type()) end)
  -- tostring d'une énumération du jeu : « gamedataQuality : Rare (2) » ; on garde le mot du milieu.
  q = q and (string.match(q, ":%s*(%S+)") or q) or nil
  typ = typ and (string.match(typ, ":%s*(%S+)") or typ) or nil
  return { tdb = TDBID.ToStringDEBUG(tdb), name = Game.GetLocalizedTextByKey(rec:DisplayName()), quality = q, type = typ,
           qty = Game.GetTransactionSystem():GetItemQuantity(pl, id) }
end

-- Écriture d'un fichier du mod (dossier courant de CET).
local function put(name, body)
  local f = io.open(name, "w")
  if f then f:write(body); f:close() end
end

-- state.json : fichier temporaire puis renommage (jamais de JSON coupé) ; si le renommage échoue (Windows refuse
-- d'écraser), écriture directe.
local function putAtomic(name, body)
  local tmp = name .. ".tmp"
  put(tmp, body)
  local ok, renamed = pcall(os.rename, tmp, name)
  if not (ok and renamed) then
    put(name, body)
    pcall(os.remove, tmp)
  end
end

local function readAll(name)
  local f = io.open(name, "r")
  if not f then return nil end
  local b = f:read("*a"); f:close()
  return b
end

-- pins.json : les repères posés avec la valeur 64 bits de leur identifiant. Un rechargement de logic.lua (reload.txt) perd
-- les tables ci-dessus alors que le jeu garde les repères : à la relecture on les désenregistre, puis le backend rejoue
-- les siens (state.pins vide). À prouver en jeu.
local function savePins()
  local list = {}
  for _, p in ipairs(pins) do
    local k = pinKeys[tostring(p.pid)]
    if k then list[#list + 1] = { pid = p.pid, x = p.x, y = p.y, z = p.z, id = k } end
  end
  putAtomic("pins.json", json.encode({ v = 1, pins = list }))
end

local function idKey(id)
  -- tostring d'un uint64 LuaJIT : « 123ULL » ; on ne garde que les chiffres.
  return string.match(tostring(id.value), "^(%d+)")
end

local function forgetSavedPins() -- à prouver en jeu
  local body = readAll("pins.json")
  if not body or body == "" then return end
  local ok, doc = pcall(json.decode, body)
  if not (ok and type(doc) == "table" and type(doc.pins) == "table") then return end
  for _, p in ipairs(doc.pins) do
    local done, err = pcall(function()
      local digits = string.match(tostring(p.id), "^%d+$")
      if not digits then error("identifiant illisible") end
      -- Littéral 64 bits LuaJIT (suffixe ULL) : seulement via loadstring, des chiffres validés au-dessus.
      local id = gameNewMappinID.new()
      id.value = loadstring("return " .. digits .. "ULL")()
      Game.GetMappinSystem():UnregisterMappin(id)
    end)
    if not done then loadErrs["pin." .. tostring(p.pid)] = tostring(err) end
  end
  putAtomic("pins.json", json.encode({ v = 1, pins = {} }))
end

-- Bloc lent : réécrit seulement si son JSON change ; le compteur blocks[key] le dit au backend.
local function putBlock(key, name, value)
  local body = json.encode(value)
  if lastJson[key] == body then return end
  lastJson[key] = body
  blocks[key] = blocks[key] + 1
  putAtomic(name, body)
end

local CW = { "SystemReplacementCW", "FrontalCortexCW", "EyesCW", "ArmsCW", "HandsCW", "MusculoskeletalSystemCW",
  "NervousSystemCW", "CardiovascularSystemCW", "ImmuneSystemCW", "IntegumentarySystemCW", "LegsCW" }
local ATTRS = { body = "Strength", ref = "Reflexes", tech = "TechnicalAbility", int = "Intelligence", cool = "Cool" }

local function stat(eid, name)
  return try("stat." .. name, function() return Game.GetStatsSystem():GetStatValue(eid, gamedataStatType[name]) end)
end

local function cyberware(pl)
  local out = {}
  local eq = try("equipData", function() return Game.GetScriptableSystemsContainer():Get(CName.new("EquipmentSystem")):GetPlayerData(pl) end)
  if not eq then return out end
  for _, a in ipairs(CW) do
    local area = gamedataEquipmentArea[a]
    local n = try("cw.n." .. a, function() return eq:GetNumberOfSlots(area) end) or 0
    local slots = {}
    for i = 0, n - 1 do
      local id = try("cw." .. a .. i, function() return eq:GetItemInEquipSlot(area, i) end)
      local info = nil
      if id and ItemID.IsValid(id) then info = try("cw.info." .. a .. i, function() return itemInfo(pl, id) end) end
      slots[#slots + 1] = info and { tdb = info.tdb, name = info.name, quality = info.quality } or false
    end
    out[a] = slots
  end
  return out
end

local function trackedEntry()
  local jm = Game.GetJournalManager()
  local e = jm:GetTrackedEntry()
  if e == nil then return nil end
  local r = { kind = "quest", id = e:GetId() }
  pcall(function() r.desc = loc(e:GetDescription()) end)
  pcall(function() local q = jm:GetParentEntry(jm:GetParentEntry(e)); r.quest = loc(q:GetTitle(jm)) end)
  -- Position de l'objectif sur la carte : même appel que le mod Knife (Teleport.ToQuestMarker), à prouver en jeu.
  pcall(function()
    local ok, positions = Game.GetMappinSystem():GetQuestMappinPositionsByObjective(jm:GetEntryHash(e))
    local p = ok and positions and positions[1]
    if p then r.pos = { x = p.x, y = p.y } end
  end)
  return r
end

-- Appel en cours : « questPhoneCallPhase : StartCall (2) », contact = CName dont le nom est en commentaire.
local function callInfo()
  local bb = Game.GetBlackboardSystem():Get(GetAllBlackboardDefs().UI_ComDevice)
  local v = FromVariant(bb:GetVariant(GetAllBlackboardDefs().UI_ComDevice.PhoneCallInformation))
  local p = tostring(v.callPhase)
  local phase = "none"
  if string.find(p, "Incoming") then phase = "incoming" elseif string.find(p, "StartCall") then phase = "active" end
  local who = tostring(v.contactName)
  who = string.match(who, "%-%-%[%[ (.-) %-%-%]%]") or who
  return { phase = phase, contact = who, audio = v.isAudioCall }
end

local function snapshot()
  seq = seq + 1
  local out = { v = 1, seq = seq, at = os.time(), inGame = false, session = session, mod = VERSION }
  local pl = Game.GetPlayer()
  if pl ~= nil then
    local eid = pl:GetEntityID()
    out.inGame = true
    local attrs = {}
    for k, s in pairs(ATTRS) do attrs[k] = stat(eid, s) end
    local pts = {}
    local dev = try("devData", function() return PlayerDevelopmentSystem.GetData(pl) end)
    if dev then
      pts.attr = try("pts.attr", function() return dev:GetDevPoints(gamedataDevelopmentPointType.Attribute) end)
      pts.perk = try("pts.perk", function() return dev:GetDevPoints(gamedataDevelopmentPointType.Primary) end)
    end
    out.player = {
      level = stat(eid, "Level"), cred = stat(eid, "StreetCred"), attrs = attrs, pts = pts,
      health = try("health", function() return Game.GetStatPoolsSystem():GetStatPoolValue(eid, gamedataStatPoolType.Health, true) end),
      humanity = try("humanityPool", function() return Game.GetStatPoolsSystem():GetStatPoolValue(eid, gamedataStatPoolType.Humanity, false) end),
      money = try("money", function() return Game.GetTransactionSystem():GetItemQuantity(pl, MarketSystem.Money()) end),
    }
    out.pos = try("pos", function()
      local p = pl:GetWorldPosition()
      return { x = p.x, y = p.y, z = p.z, yaw = pl:GetWorldYaw() }
    end)
    out.cyberware = cyberware(pl)
    local t = try("tracked", trackedEntry)
    if t == nil and trackedPin then t = { kind = "pin", id = trackedPin } end
    out.tracked = t
    out.call = try("callInfo", callInfo)
  end
  out.blocks = blocks
  out.pins = pins
  out.acks = acks
  out.errors = errs
  return out
end

-- Blocs lents ---------------------------------------------------------------------------------------------------------

local function inventoryBlock()
  local pl = Game.GetPlayer()
  if pl == nil then return end
  local _, list = Game.GetTransactionSystem():GetItemList(pl)
  local items = {}
  for i = 1, #(list or {}) do
    local it = try("inv." .. i, function() return itemInfo(pl, list[i]:GetID()) end)
    if it then
      -- Utilisable : consommable (type « Con_… »), déduction du type de l'objet, sans appel de plus.
      it.usable = it.type ~= nil and string.find(it.type, "^Con_") ~= nil and it.type ~= "Con_Ammo"
      items[#items + 1] = it
    end
  end
  putBlock("inv", "inventory.json", { v = 1, items = items })
end

local function contactsBlock()
  local jm = Game.GetJournalManager()
  local contacts, threads = {}, {}
  local list = try("contacts", function() return MessengerUtils.GetCallableAndNonEmptyContacts(jm) end)
  for i = 1, #(list or {}) do
    local c = list[i]
    contacts[#contacts + 1] = { id = c.contactId, name = loc(c.localizedName), callable = c.isCallable, unread = c.unreadMessegeCount or 0 }
  end
  local th = try("threads", function() return MessengerUtils.GetSimpleContactDataArray(jm, true, true, true) end)
  -- Tous les fils (28 sur la partie d'Ahmed) : la tablette filtre (`unread > 0 or canReply`).
  for i = 1, #(th or {}) do
    local c = th[i]
    threads[#threads + 1] = { hash = tostring(c.hash), name = loc(c.localizedName), preview = loc(c.lastMesssagePreview),
      unread = #(c.unreadMessages or {}), multi = isMulti(c), canReply = c.playerCanReply == true }
  end
  putBlock("contacts", "contacts.json", { v = 1, contacts = contacts, threads = threads })
end

local function questsBlock()
  local jm = Game.GetJournalManager()
  local quests = {}
  local t = try("tracked", trackedEntry)
  -- Liste complète : GetQuests rendait une table vide pendant le spike (forme du paramètre à trouver, à prouver en jeu).
  local list = try("quests", function()
    local ctx = JournalRequestContext.new()
    ctx.stateFilter.active = true
    local a, b = jm:GetQuests(ctx)
    if type(a) == "table" and #a > 0 then return a end
    return b
  end)
  for i = 1, math.min(#(type(list) == "table" and list or {}), 30) do
    local q = list[i]
    local e = try("q." .. i, function() return { id = q:GetId(), title = loc(q:GetTitle(jm)), kind = "quest", tracked = false } end)
    if e then quests[#quests + 1] = e end
  end
  if t then
    local found = false
    for _, q in ipairs(quests) do
      if t.quest and q.title == t.quest then q.objective = t.desc; q.tracked = true; found = true end
    end
    if not found then quests[#quests + 1] = { id = t.id, title = t.quest or "", kind = "quest", objective = t.desc, tracked = true } end
  end
  putBlock("quests", "quests.json", { v = 1, quests = quests })
end

-- Ordres ----------------------------------------------------------------------------------------------------------------

local function phoneRequest(phase, caller, addressee, mode)
  local ps = Game.GetScriptableSystemsContainer():Get(CName.new("PhoneSystem"))
  local r = questTriggerCallRequest.new()
  r.addressee = CName.new(addressee); r.caller = CName.new(caller)
  r.callPhase = phase; r.callMode = mode or questPhoneCallMode.Audio
  ps:QueueRequest(r)
end

local COMMANDS = {}

COMMANDS.call = function(c)
  local ps = Game.GetScriptableSystemsContainer():Get(CName.new("PhoneSystem"))
  if not ps:IsCallingEnabled() then return "blocked" end
  phoneRequest(questPhoneCallPhase.IncomingCall, "Player", tostring(c.contact), questPhoneCallMode.Video)
  return "calling"
end

COMMANDS.answer = function() -- à prouver en jeu
  local c = callInfo()
  if c.phase ~= "incoming" then return "none" end
  phoneRequest(questPhoneCallPhase.StartCall, c.contact, "Player")
  return "answered"
end

COMMANDS.hangup = function() -- à prouver en jeu
  local c = callInfo()
  if c.phase == "none" then return "none" end
  phoneRequest(questPhoneCallPhase.EndCall, c.contact, "Player")
  return "hungup"
end

COMMANDS.summon = function() -- à prouver en jeu (IsSummoningVehiclesRestricted a répondu « requires 0 parameter(s) » au spike)
  Game.GetPlayer():GetQuickSlotsManager():SummonActiveVehicle(true)
  return "summoned"
end

COMMANDS.use = function(c)
  local pl = Game.GetPlayer()
  local id = ItemID.FromTDBID(TweakDBID.new(tostring(c.tdb)))
  if Game.GetTransactionSystem():GetItemQuantity(pl, id) < 1 then return "none" end
  -- Seuls les consommables (type « Con_… », hors munitions) : même extraction du type que le bloc inventaire.
  local ok, typ = pcall(function() return tostring(TweakDBInterface.GetItemRecord(ItemID.GetTDBID(id)):ItemType():Type()) end)
  typ = ok and typ and (string.match(typ, ":%s*(%S+)") or typ) or nil
  if not (typ and string.find(typ, "^Con_") and typ ~= "Con_Ammo") then return "none" end
  ItemActionsHelper.ConsumeItem(pl, id, true)
  return "used"
end

-- `id` est celui de l'ordre (acks) : la cible du suivi (quête ou repère) est dans `ref`.
COMMANDS.track = function(c) -- à prouver en jeu
  local jm = Game.GetJournalManager()
  if c.kind == "pin" then
    local id = mappinIds[tostring(c.ref)]
    if not id then return "unknown" end
    Game.GetMappinSystem():SetMappinTrackingAlternative(id, true)
    trackedPin = c.ref
    return "tracked"
  end
  local ctx = JournalRequestContext.new()
  ctx.stateFilter.active = true
  local a, b = jm:GetQuests(ctx)
  local list = (type(a) == "table" and #a > 0) and a or b
  for i = 1, #(type(list) == "table" and list or {}) do
    if list[i]:GetId() == c.ref then jm:TrackEntry(list[i]); trackedPin = nil; return "tracked" end
  end
  return "unknown"
end

COMMANDS.pin = function(c)
  local pid = tostring(c.pid)
  if mappinIds[pid] then return "exists" end
  local function finite(v) local n = tonumber(v); return n ~= nil and n == n and n ~= math.huge and n ~= -math.huge end
  if not (finite(c.x) and finite(c.y) and finite(c.z)) then return "invalide" end
  local data = MappinData.new()
  data.mappinType = TweakDBID.new("Mappins.DefaultStaticMappin")
  data.variant = gamedataMappinVariant.CustomPositionVariant
  data.visibleThroughWalls = true
  local id = Game.GetMappinSystem():RegisterMappin(data, Vector4.new(tonumber(c.x), tonumber(c.y), tonumber(c.z), 1))
  mappinIds[pid] = id
  pins[#pins + 1] = { pid = c.pid, x = c.x, y = c.y, z = c.z }
  local okKey, key = pcall(idKey, id)
  if okKey and key then pinKeys[pid] = key else errs["pinKey"] = tostring(key) end
  pcall(savePins)
  return "pinned"
end

COMMANDS.unpin = function(c)
  local pid = tostring(c.pid)
  local id = mappinIds[pid]
  if not id then return "unknown" end
  Game.GetMappinSystem():UnregisterMappin(id)
  mappinIds[pid] = nil
  pinKeys[pid] = nil
  for i = #pins, 1, -1 do if tostring(pins[i].pid) == pid then table.remove(pins, i) end end
  if trackedPin and tostring(trackedPin) == pid then trackedPin = nil end
  pcall(savePins)
  return "unpinned"
end

-- Les refus sont des erreurs sans position (niveau 0) : le message devient `res` et l'ack `ok` est faux.
COMMANDS.thread = function(c)
  local j = Game.GetJournalManager()
  local t = findThread(j, c.hash)
  if not t then error("fil-introuvable", 0) end
  local msgs, choices = threadData(j, t)
  local player = enum(gameMessageSender.Player)
  local outMsgs, outChoices, toMark = {}, {}, {}
  for i = 1, #(msgs or {}) do
    local m = msgs[i]
    local mine = true -- une réponse déjà envoyée (entrée de choix) est celle du joueur
    if m:IsA("gameJournalPhoneMessage") then
      mine = enum(m:GetSender()) == player
      if not j:IsEntryVisited(m) then toMark[#toMark + 1] = m end
    end
    outMsgs[#outMsgs + 1] = { hash = tostring(j:GetEntryHash(m)), text = loc(m:GetText()), player = mine }
  end
  for i = 1, #(choices or {}) do
    outChoices[#outChoices + 1] = { hash = tostring(j:GetEntryHash(choices[i])), text = loc(choices[i]:GetText()) }
  end
  putAtomic("thread.json", json.encode({ v = 1, cmd = c.id, hash = tostring(c.hash), version = threadVersion(j, msgs),
    messages = outMsgs, choices = outChoices }))
  -- Comme SetVisited du jeu : le fil lu ne reste pas « non lu ».
  for _, m in ipairs(toMark) do pcall(function() j:SetEntryVisited(m, true) end) end
  return "ok"
end

COMMANDS.reply = function(c)
  local j = Game.GetJournalManager()
  local t = findThread(j, c.hash)
  if not t then error("fil-introuvable", 0) end
  local msgs, choices = threadData(j, t)
  if threadVersion(j, msgs) ~= tostring(c.version) then error("choix-perime", 0) end
  local pick = nil
  for i = 1, #(choices or {}) do
    if tostring(j:GetEntryHash(choices[i])) == tostring(c.choice) then pick = choices[i] end
  end
  if not pick then error("choix-perime", 0) end
  for i = 1, #choices do
    local r = choices[i]
    if r ~= pick then
      j:SetEntryVisited(r, true)
      j:ChangeEntryStateByHash(u32(j:GetEntryHash(r)), gameJournalEntryState.Inactive, JournalNotifyOption.Notify)
    end
  end
  j:SetEntryVisited(pick, true)
  j:ChangeEntryStateByHash(u32(j:GetEntryHash(pick)), gameJournalEntryState.Succeeded, JournalNotifyOption.Notify)
  return "ok"
end

local function readCommands()
  local body = readAll("commands.json")
  if not body or body == "" then return nil end
  local ok, doc = pcall(json.decode, body)
  if ok and type(doc) == "table" and type(doc.cmds) == "table" then return doc.cmds end
  return nil
end

local function runCommands()
  local cmds = readCommands()
  if not cmds then return end
  if lastCmd == nil then
    -- Premier passage : ce qui est déjà dans le fichier a été traité (ou périmé), seuls les nouveaux ordres s'exécutent.
    lastCmd = 0
    for _, c in ipairs(cmds) do if type(c.id) == "number" and c.id > lastCmd then lastCmd = c.id end end
    return
  end
  table.sort(cmds, function(a, b) return (a.id or 0) < (b.id or 0) end)
  for _, c in ipairs(cmds) do
    if type(c.id) == "number" and c.id > lastCmd then
      lastCmd = c.id
      local run = COMMANDS[tostring(c.t)]
      local ok, res
      local okP, player = pcall(function() return Game.GetPlayer() end)
      if not okP then ok, res = false, "jeu indisponible : " .. tostring(player)
      elseif player == nil then ok, res = false, "hors partie"
      elseif run then ok, res = pcall(run, c)
      else ok, res = false, "inconnu" end
      acks[#acks + 1] = { id = c.id, ok = ok and true or false, res = tostring(res) }
      while #acks > 20 do table.remove(acks, 1) end
    end
  end
end

-- Session : +1 quand le joueur apparaît après avoir été absent (chargement de partie), gardé dans session.txt.
local function trackSession()
  local present = Game.GetPlayer() ~= nil
  if present and not hadPlayer then
    session = session + 1
    -- Le jeu a oublié les repères de la partie d'avant : le backend renvoie les siens (pins/acks).
    pins, mappinIds, pinKeys, trackedPin = {}, {}, {}, nil
    pcall(savePins)
    put("session.txt", tostring(session))
  end
  hadPlayer = present
end

local ok0, p0 = pcall(function() return Game.GetPlayer() end)
hadPlayer = ok0 and p0 ~= nil
session = tonumber(readAll("session.txt") or "") or 0

pcall(forgetSavedPins)

local SLOW = { inventoryBlock, contactsBlock, questsBlock }

return { update = function(dt)
  acc = acc + dt
  if acc < every then return end
  acc = 0
  tick = tick + 1
  errs = loadErrs
  loadErrs = {}
  pcall(trackSession)
  pcall(runCommands)
  -- Un bloc lent par seconde, à tour de rôle (chacun toutes les 5 s), jamais pendant l'écran de chargement.
  local slot = tick % 5
  if SLOW[slot] and hadPlayer then
    local ok, e = pcall(SLOW[slot])
    if not ok then errs["slow" .. slot] = tostring(e) end
  end
  local ok, state = pcall(snapshot)
  if not ok then state = { v = 1, seq = seq, at = os.time(), inGame = false, session = session, mod = VERSION, blocks = blocks,
                           pins = pins, acks = acks, errors = { fatal = tostring(state) } } end
  local enc, body = pcall(json.encode, state)
  if enc then putAtomic("state.json", body) end
end }
